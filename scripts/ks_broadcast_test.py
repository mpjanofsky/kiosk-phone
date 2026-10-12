#!/usr/bin/env python3
"""Kiosk Satellite intercom broadcast test (paging bridge, step 1). Throwaway.

Sends a one-way intercom broadcast (tone or WAV) to one kiosk and reports
timing, kiosk status and any callbacks the kiosk sends to our listener.

Protocol ported from Eibelucas/Kiosk-Satellite-SIP kiosk_sip/app/intercom.py
(Apache-2.0) and the KS docs. The key is read only from KS_INTERCOM_KEY and
is never printed, logged or written anywhere.

Requires: Python 3.8+, aiohttp. Optional: ffmpeg (to convert odd WAV formats).
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import math
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import uuid
import wave

from aiohttp import ClientError, ClientSession, ClientTimeout, WSMsgType, web

SENDER_ID = hashlib.sha256(b"ks-broadcast-test").hexdigest()[:16]  # 16 hex, like real KS ids
VERSION = "26.10.12"
FRAME_MS = 80
FRAME_BYTES = 2560  # 80 ms * 16 kHz * 2 bytes, PCM16 mono
RATE = 16000

EXIT = {"ok": 0, "config": 2, "unreachable": 3, "key": 4, "off": 5, "dnd": 6,
        "busy": 7, "tls": 8, "refused": 9, "other": 10}


class Fail(Exception):
    def __init__(self, kind, msg):
        super().__init__(msg)
        self.kind = kind


def scrub(text):
    """Remove URL query strings (they carry the one-use token) from error text."""
    return re.sub(r"\?[^\s'\")>]*", "?<redacted>", str(text))


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def key_digest(key):
    return hashlib.sha256(key.encode()).hexdigest()[:8]


def issue_token(key, call, sender=SENDER_ID):
    claims = {"intercom": call, "from": sender, "n": secrets.token_hex(12),
              "exp": int(time.time() * 1000) + 60000}  # exp in milliseconds
    payload = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).decode()
    sig = base64.urlsafe_b64encode(hmac.digest(("intercom:" + key).encode(), payload.encode(), "sha256")).decode()
    return payload + "." + sig


def detect_ip(host):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((host, 9))  # UDP connect sends nothing; just selects the route
        return s.getsockname()[0]
    finally:
        s.close()


# ---------------------------------------------------------------- audio

def tone_pcm(seconds=5.0, freq=440.0, amp=0.3):
    n = int(RATE * seconds)
    return b"".join(struct.pack("<h", int(amp * 32767 * math.sin(2 * math.pi * freq * i / RATE)))
                    for i in range(n))


def wav_pcm(path):
    try:
        with wave.open(path, "rb") as w:
            if (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, RATE):
                return w.readframes(w.getnframes())
    except (wave.Error, EOFError):
        pass
    if not shutil.which("ffmpeg"):
        raise Fail("config", f"{path} is not 16 kHz mono PCM16 WAV and ffmpeg is not installed to convert it")
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-ac", "1",
                        "-ar", str(RATE), "-"], capture_output=True)
    if r.returncode or not r.stdout:
        raise Fail("config", "ffmpeg could not convert the audio file")
    return r.stdout


def frames_of(pcm):
    if len(pcm) % FRAME_BYTES:
        pcm += b"\x00" * (FRAME_BYTES - len(pcm) % FRAME_BYTES)
    return [pcm[i:i + FRAME_BYTES] for i in range(0, len(pcm), FRAME_BYTES)]



def start_mdns(name, port, ip):
    """Advertise ourselves as a KS peer (_kiosk-satellite._tcp) for the life of this run.

    Uses dns-sd (macOS) or avahi-publish-service (Linux). Returns the Popen; the
    registration vanishes when that process is terminated.
    """
    txt = [f"id={SENDER_ID}", f"name={name}", f"version={VERSION}", f"port={port}",
           "host=ks-broadcast-test"]
    if shutil.which("dns-sd"):
        cmd = ["dns-sd", "-R", f"ks-{SENDER_ID}", "_kiosk-satellite._tcp", "local", str(port)] + txt
    elif shutil.which("avahi-publish-service"):
        cmd = ["avahi-publish-service", f"ks-{SENDER_ID}", "_kiosk-satellite._tcp", str(port)] + txt
    else:
        raise Fail("config", "--mdns needs dns-sd (macOS) or avahi-publish-service (Linux)")
    return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def stop_mdns(proc):
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(3)
        except subprocess.TimeoutExpired:
            proc.kill()

# ---------------------------------------------------------------- listener

class Listener:
    def __init__(self, ip, port, key, name, reply_frames=None, reply_seconds=15.0):
        self.ip, self.port, self.key, self.name = ip, port, key, name
        self.reply_frames = reply_frames or frames_of(tone_pcm(3.0, 880.0))
        self.reply_seconds = reply_seconds
        self.events = []
        self.runner = None

    async def start(self):
        app = web.Application(client_max_size=65536)
        app.router.add_get("/api/intercom/identity", self.identity)
        app.router.add_post("/api/intercom/call", self.incoming_call)
        app.router.add_get("/api/intercom/audio/{call}", self.audio)
        app.router.add_route("*", "/{tail:.*}", self.other)
        self.runner = web.AppRunner(app, access_log=None)
        await self.runner.setup()
        try:
            await web.TCPSite(self.runner, self.ip, self.port).start()
        except OSError as e:
            raise Fail("config", f"cannot listen on {self.ip}:{self.port}: {e}")

    async def stop(self):
        if self.runner:
            await self.runner.cleanup()

    async def identity(self, req):
        self.events.append(f"identity probe from {req.remote}")
        log(f"LISTENER: identity probe from {req.remote}")
        return web.json_response({"id": SENDER_ID, "name": self.name, "version": VERSION,
                                  "enabled": True, "key": key_digest(self.key), "dnd": False,
                                  "endpoint": {"port": self.port, "tls": False}})

    def note(self, line):
        self.events.append(line)
        log(f"LISTENER: {line}")

    async def incoming_call(self, req):
        """Kiosk calling us back (Reply on the broadcast card). Answer as an auto-answering callee."""
        try:
            body = await req.json()
        except Exception:
            body = {}
        frm = body.get("from") if isinstance(body, dict) else None
        kind = body.get("kind") if isinstance(body, dict) else None
        cid = body.get("call") if isinstance(body, dict) else None
        peer = frm.get("name") if isinstance(frm, dict) else None
        self.note(f"POST /api/intercom/call kind={kind} from={peer!r} (replying status=auto)")
        if kind != "call" or not isinstance(cid, str):
            return web.json_response({"status": "refused"})
        # Callee side of the handshake: after replying 'auto', tell the caller we answered
        # (the kiosk opens the audio socket only after this). Short delay mimics the countdown.
        port = frm.get("port") if isinstance(frm, dict) else None
        if type(port) is int and req.remote:
            asyncio.create_task(self.send_answer(req.remote, port, cid))
        else:
            self.note("incoming call had no usable from.port; cannot answer")
        return web.json_response({"status": "auto"})

    async def send_answer(self, host, port, cid):
        await asyncio.sleep(1.0)
        try:
            async with ClientSession(timeout=ClientTimeout(total=5)) as http:
                async with http.post(f"http://{host}:{port}/api/intercom/call/{cid}",
                                     json={"action": "answer"}, allow_redirects=False,
                                     headers={"Authorization": "Bearer " + issue_token(self.key, cid)}) as r:
                    self.note(f"sent action=answer to kiosk -> HTTP {r.status}")
        except (ClientError, asyncio.TimeoutError, OSError) as e:
            self.note(f"answer to kiosk failed: {scrub(e)}")

    async def audio(self, req):
        """Audio WebSocket opened by the kiosk after our 'auto' answer. Log what arrives, send a tone back."""
        ws = web.WebSocketResponse(max_msg_size=4096, heartbeat=10)
        await ws.prepare(req)
        self.note("audio websocket opened by kiosk")
        got = 0
        peak = 0
        t0 = time.monotonic()
        sender = None

        async def tone_back():
            try:
                await ws.send_json({"type": "talk", "on": True})
                start = time.monotonic()
                i = 0
                while i * FRAME_MS / 1000 < self.reply_seconds and not ws.closed:
                    fr = self.reply_frames[i % len(self.reply_frames)]
                    await asyncio.sleep(max(0, start + i * FRAME_MS / 1000 - time.monotonic()))
                    await ws.send_bytes(fr)
                    i += 1
                self.note(f"reply audio finished after {i} frames; ending call")
                await ws.send_json({"type": "end"})
            except Exception:
                pass
        sender = asyncio.create_task(tone_back())
        try:
            async for m in ws:
                if m.type == WSMsgType.BINARY:
                    got += 1
                    n = len(m.data) // 2
                    if n:
                        peak = max(peak, max(abs(v) for v in struct.unpack(f"<{n}h", m.data[:n * 2])))
                    if got == 1:
                        self.note(f"first mic frame after {time.monotonic() - t0:.2f}s ({len(m.data)} bytes)")
                elif m.type == WSMsgType.TEXT:
                    self.note(f"audio ws text: {m.data[:80]}")
                elif m.type in (WSMsgType.CLOSE, WSMsgType.ERROR):
                    break
        finally:
            sender.cancel()
            self.note(f"audio websocket closed: {got} mic frames, peak amplitude {peak}/32767")
        return ws

    async def other(self, req):
        detail = ""
        try:
            body = await req.json()
            if isinstance(body, dict):
                detail = " ".join(f"{k}={body[k]}" for k in ("action", "status") if k in body)
        except Exception:
            pass
        line = f"{req.method} {req.path} from {req.remote} {detail}".strip()
        self.events.append(line)
        log(f"LISTENER: {line}")
        return web.json_response({"ok": True})


# ---------------------------------------------------------------- one run

async def one_run(args, key, adv_ip, frames, listener):
    res = {"status": None, "first_frame_s": None, "frames": 0, "error": None, "callbacks": 0}
    cb0 = len(listener.events)
    base = f"http://{args.host}:{args.port}"
    call_id = str(uuid.uuid4())
    t0 = time.monotonic()
    answered = False
    ws = None
    call_base = base
    timeout = ClientTimeout(total=8)
    async with ClientSession(timeout=timeout) as http:
        async def post(path, body):
            async with http.post(call_base + path, json=body, allow_redirects=False,
                                 headers={"Authorization": "Bearer " + issue_token(key, call_id)}) as r:
                raw = await r.content.read(4097)
                try:
                    data = json.loads(raw) if raw else {}
                except ValueError:
                    data = {}
                return r.status, data

        try:
            # 2/3. identity
            try:
                async with http.get(base + "/api/intercom/identity", allow_redirects=False) as r:
                    if r.status != 200:
                        raise Fail("unreachable", f"identity returned HTTP {r.status}")
                    ident = json.loads(await r.content.read(4097))
            except (ClientError, asyncio.TimeoutError, OSError) as e:
                raise Fail("unreachable", f"cannot reach {args.host}:{args.port}: {scrub(e)}")
            ep = ident.get("endpoint") or {}
            log(f"kiosk: name={ident.get('name')!r} version={ident.get('version')} "
                f"enabled={ident.get('enabled')} dnd={ident.get('dnd')} "
                f"endpoint.port={ep.get('port')} endpoint.tls={ep.get('tls')}")
            if not ident.get("enabled"):
                raise Fail("off", "Intercom is disabled on the kiosk")
            if ident.get("key") != key_digest(key):
                raise Fail("key", "key digest mismatch: KS_INTERCOM_KEY differs from the kiosk's key")
            if ep.get("tls"):
                raise Fail("tls", "kiosk requires TLS; this test is plaintext only and will not downgrade")
            if ident.get("dnd"):
                raise Fail("dnd", "kiosk reports Do Not Disturb (or Lockdown Mode)")
            port = ep.get("port", args.port)
            if type(port) is not int or not 1024 <= port <= 65535:
                raise Fail("other", "kiosk reported an invalid intercom port")
            call_base = f"http://{args.host}:{port}"

            # 4. call
            code, reply = await post("/api/intercom/call", {
                "call": call_id, "kind": "broadcast",
                "from": {"id": SENDER_ID, "name": args.name[:80], "address": adv_ip,
                         "port": args.listen_port, "version": VERSION, "tls": False}})
            status = reply.get("status")
            res["status"] = status if code == 200 else f"http{code}"
            log(f"call -> HTTP {code}, status={status}")
            if code == 403 or status == "key":
                raise Fail("key", "kiosk rejected our key (HTTP 403 / status key)")
            if code == 409 or code == 426 or status == "tls":
                raise Fail("tls", f"encryption mismatch (HTTP {code}); plaintext only here")
            if status == "busy":
                raise Fail("busy", "kiosk is busy")
            if status == "dnd":
                raise Fail("dnd", "kiosk is in Do Not Disturb / Lockdown Mode")
            if status == "off":
                raise Fail("off", "intercom is off on the kiosk")
            if status == "refused":
                raise Fail("refused", "kiosk refuses announcements (check 'Accept announcements')")
            if code != 200 or status not in {"ringing", "auto", "listening"}:
                raise Fail("other", f"unexpected call response HTTP {code} status={status}")
            answered = True  # broadcasts are treated as accepted, as in the reference

            # 5. audio websocket
            try:
                ws = await http.ws_connect(
                    f"ws://{args.host}:{port}/api/intercom/audio/{call_id}",
                    params={"token": issue_token(key, call_id)}, heartbeat=10,
                    compress=0, max_msg_size=4096)
                await ws.send_json({"type": "talk", "on": True})
            except (ClientError, asyncio.TimeoutError, OSError) as e:
                raise Fail("other", f"audio websocket failed: {scrub(e)}")

            # 6. stream, paced to wall clock
            start = time.monotonic()
            for i, fr in enumerate(frames):
                due = start + i * FRAME_MS / 1000
                await asyncio.sleep(max(0, due - time.monotonic()))
                if ws.closed or ws.close_code is not None:
                    res["ended_by_kiosk_s"] = time.monotonic() - start
                    break
                try:
                    await ws.send_bytes(fr)
                except (ConnectionError, ClientError, RuntimeError):
                    res["ended_by_kiosk_s"] = time.monotonic() - start
                    break
                res["frames"] += 1
                if res["first_frame_s"] is None:
                    res["first_frame_s"] = time.monotonic() - t0
            await asyncio.sleep(0.2)
        except Fail as f:
            res["error"] = (f.kind, str(f))
        except (ClientError, asyncio.TimeoutError, OSError) as e:
            res["error"] = ("other", scrub(e))
        finally:
            # 7. teardown
            if ws is not None and not ws.closed:
                try:
                    await ws.send_json({"type": "end"})
                    await ws.close()
                except Exception:
                    pass
            if res["status"] in {"ringing", "auto", "listening"}:
                try:
                    await post("/api/intercom/call/" + call_id,
                               {"action": "hangup" if answered else "cancel"})
                except Exception:
                    pass
    if res.get("ended_by_kiosk_s") is not None:
        log(f"kiosk closed the audio socket after {res['ended_by_kiosk_s']:.1f}s "
            f"({res['frames']} frames sent; Dismiss/hangup on the card)")
    res["callbacks"] = len(listener.events) - cb0
    return res


async def main_async(args):
    key = os.environ.get("KS_INTERCOM_KEY", "")
    if not key:
        raise Fail("config", "KS_INTERCOM_KEY is not set. Use: read -rs KS_INTERCOM_KEY && export KS_INTERCOM_KEY")
    adv_ip = args.advertise_ip or detect_ip(args.host)
    log(f"advertising {adv_ip}:{args.listen_port}; target {args.host}:{args.port}")
    if args.wav:
        pcm = wav_pcm(args.wav)
        src = args.wav
    else:
        pcm, src = tone_pcm(), "5 s 440 Hz tone"
    frames = frames_of(pcm)
    log(f"audio: {src}, {len(frames)} frames ({len(frames) * FRAME_MS / 1000:.1f} s)")

    listener = Listener(adv_ip, args.listen_port, key, args.name, frames, args.reply_seconds)
    await listener.start()
    results = []
    mdns = None
    try:
        if args.mdns:
            mdns = start_mdns(args.name, args.listen_port, adv_ip)
            log(f"mDNS: advertising as a KS peer; waiting {args.mdns_wait:.0f}s for kiosks to notice "
                "(removed when this script exits)")
            await asyncio.sleep(args.mdns_wait)
        for n in range(1, args.repeat + 1):
            if args.repeat > 1:
                log(f"--- run {n}/{args.repeat} ---")
            r = await one_run(args, key, adv_ip, frames, listener)
            results.append(r)
            e = r["error"]
            ff = f"{r['first_frame_s']:.3f}s" if r["first_frame_s"] is not None else "n/a"
            log(f"result: status={r['status']} first_frame={ff} frames={r['frames']} "
                f"callbacks={r['callbacks']} error={e[1] if e else 'none'}")
            if n < args.repeat:
                await asyncio.sleep(max(args.interval, 1.0))  # identity is limited to 1/s
        if args.linger and results and results[-1]["error"] is None:
            log(f"listening {args.linger:.0f}s for Reply/Dismiss callbacks (Ctrl-C to skip)")
            try:
                await asyncio.sleep(args.linger)
            except asyncio.CancelledError:
                pass
    finally:
        stop_mdns(mdns)
        if mdns:
            log("mDNS: advertisement removed")
        await listener.stop()

    ok = [r for r in results if r["error"] is None]
    print("\n=== summary ===")
    print(f"runs={len(results)} success={len(ok)} failed={len(results) - len(ok)}")
    statuses = {}
    for r in results:
        statuses[r["status"]] = statuses.get(r["status"], 0) + 1
    print("statuses:", statuses)
    print("callbacks received:", len(listener.events))
    for ev in listener.events:
        print("  ", ev)
    errs = [r["error"] for r in results if r["error"]]
    for kind, msg in errs:
        print(f"ERROR [{kind}]: {msg}")
    if errs:
        return EXIT.get(errs[-1][0], EXIT["other"])
    return 0


def main():
    p = argparse.ArgumentParser(description="KS intercom one-way broadcast test")
    p.add_argument("--host", default=os.environ.get("KS_HOST"), required=not os.environ.get("KS_HOST"),
                   help="kiosk address (or env KS_HOST)")
    p.add_argument("--port", type=int, default=2324)
    p.add_argument("--advertise-ip", help="our LAN IP as the kiosk should see it (default: auto)")
    p.add_argument("--listen-port", type=int, default=2325)
    p.add_argument("--wav", help="audio file; 16 kHz mono PCM16 WAV used as-is, else converted with ffmpeg")
    p.add_argument("--name", default="FreePBX Page Test")
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--interval", type=float, default=10.0, help="seconds between repeats (min 1)")
    p.add_argument("--linger", type=float, default=15.0,
                   help="seconds to keep the listener up after a single run to catch Reply/Dismiss (0 = off)")
    p.add_argument("--reply-seconds", type=float, default=15.0,
                   help="how long to play audio back if the kiosk calls us (Reply); uses the same audio, looped")
    p.add_argument("--mdns", action="store_true",
                   help="advertise this script as a KS peer via mDNS while it runs (test of Reply)")
    p.add_argument("--mdns-wait", type=float, default=8.0, help="seconds to wait after advertising")
    args = p.parse_args()
    try:
        code = asyncio.run(main_async(args))
    except Fail as f:
        print(f"ERROR [{f.kind}]: {f}", file=sys.stderr)
        code = EXIT.get(f.kind, EXIT["other"])
    except KeyboardInterrupt:
        code = 130
    sys.exit(code)


if __name__ == "__main__":
    main()
