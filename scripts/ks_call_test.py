#!/usr/bin/env python3
"""KS intercom caller-side `kind:"call"` protocol spike (inbound calls, Step 0). Throwaway.

Rings one kiosk with a real call, logs everything the kiosk sends back (callback
paths, bodies, auth), and after Answer opens the audio socket and logs mic frames.
Needs one person to press Answer (or Decline) on the kiosk. Key from KS_INTERCOM_KEY only.
Reuses helpers from ks_broadcast_test.py.
"""
import argparse, asyncio, json, os, struct, sys, time, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aiohttp import ClientError, ClientSession, ClientTimeout, WSMsgType, web
import ks_broadcast_test as b

events = asyncio.Queue()


def note(line):
    b.log(line)


class Listener:
    def __init__(self, ip, port):
        self.ip, self.port, self.runner = ip, port, None

    async def start(self):
        app = web.Application(client_max_size=65536)
        app.router.add_route("*", "/{tail:.*}", self.handle)
        self.runner = web.AppRunner(app, access_log=None)
        await self.runner.setup()
        await web.TCPSite(self.runner, self.ip, self.port).start()

    async def handle(self, req):
        body = ""
        try:
            body = json.dumps(await req.json())
        except Exception:
            pass
        auth = "bearer" if req.headers.get("Authorization", "").startswith("Bearer ") else "none"
        if "token" in req.query:
            auth = "query-token"
        note(f"CALLBACK {req.method} {req.path} auth={auth} body={body[:200]}")
        if req.headers.get("Upgrade", "").lower() == "websocket":
            note("kiosk opened a WebSocket to us (unexpected for caller flow)")
        await events.put((req.method, req.path, body))
        return web.json_response({"ok": True})


async def run(args):
    key = os.environ.get("KS_INTERCOM_KEY", "")
    if not key:
        raise b.Fail("config", "KS_INTERCOM_KEY is not set")
    adv_ip = args.advertise_ip or b.detect_ip(args.host)
    lst = Listener(adv_ip, args.listen_port)
    await lst.start()
    cid = str(uuid.uuid4())
    base = f"http://{args.host}:{args.port}"
    async with ClientSession(timeout=ClientTimeout(total=8)) as http:
        async with http.get(base + "/api/intercom/identity") as r:
            ident = await r.json()
        port = ident["endpoint"]["port"]
        note(f"identity: dnd={ident.get('dnd')} enabled={ident.get('enabled')} tls={ident['endpoint'].get('tls')}")
        cb = f"http://{args.host}:{port}"

        async def post(path, body):
            async with http.post(cb + path, json=body, headers={"Authorization": "Bearer " + b.issue_token(key, cid)}) as r:
                return r.status, await r.json(content_type=None)

        t0 = time.monotonic()
        code, reply = await post("/api/intercom/call", {
            "call": cid, "kind": "call",
            "from": {"id": b.SENDER_ID, "name": args.name, "address": adv_ip, "port": args.listen_port,
                     "version": b.VERSION, "tls": False}})
        note(f"call -> HTTP {code} {reply} ({time.monotonic() - t0:.2f}s). Press Answer or Decline on the kiosk now.")
        if reply.get("status") not in ("ringing", "auto", "listening"):
            return
        answered = False
        try:
            while True:
                m, path, body = await asyncio.wait_for(events.get(), args.ring_wait)
                if "answer" in body:
                    answered = True
                    note(f"answered after {time.monotonic() - t0:.1f}s")
                    break
                if any(w in body for w in ("decline", "missed", "hangup", "cancel")):
                    note("call ended before answer")
                    break
        except asyncio.TimeoutError:
            note(f"nobody answered within {args.ring_wait:.0f}s; cancelling")
            code, reply = await post("/api/intercom/call/" + cid, {"action": "cancel"})
            note(f"cancel -> {code} {reply}")
            await asyncio.sleep(1)
            return
        if not answered:
            return
        frames_in = peak = 0
        texts = []
        first = None
        try:
            ws = await http.ws_connect(f"ws://{args.host}:{port}/api/intercom/audio/{cid}",
                                       params={"token": b.issue_token(key, cid)}, heartbeat=10, max_msg_size=8192)
        except Exception as e:
            note(f"audio ws failed: {b.scrub(e)}")
            await post("/api/intercom/call/" + cid, {"action": "hangup"})
            return
        note("audio ws open (we opened it)")
        await ws.send_json({"type": "talk", "on": True})
        tone = b.frames_of(b.tone_pcm(args.seconds, 440.0, 0.1))

        async def sender():
            start = time.monotonic()
            for i, fr in enumerate(tone):
                await asyncio.sleep(max(0, start + i * 0.08 - time.monotonic()))
                if ws.closed:
                    return
                await ws.send_bytes(fr)
        s = asyncio.create_task(sender())
        end = time.monotonic() + args.seconds + 1
        while time.monotonic() < end and not ws.closed:
            try:
                msg = await asyncio.wait_for(ws.receive(), 0.5)
            except asyncio.TimeoutError:
                continue
            if msg.type == WSMsgType.BINARY:
                n = len(msg.data) // 2
                frames_in += 1
                pk = max((abs(v) for v in struct.unpack(f"<{n}h", msg.data[:n * 2])), default=0)
                peak = max(peak, pk)
                if first is None:
                    first = time.monotonic() - t0
                    note(f"first mic frame: {len(msg.data)} bytes")
            elif msg.type == WSMsgType.TEXT:
                texts.append(msg.data[:80])
                note(f"ws text: {msg.data[:80]}")
            else:
                note(f"ws closed by kiosk: {msg.type} {ws.close_code}")
                break
        s.cancel()
        note(f"mic frames={frames_in} peak={peak}/32767 texts={texts}")
        try:
            await ws.send_json({"type": "end"})
            await ws.close()
        except Exception:
            pass
        code, reply = await post("/api/intercom/call/" + cid, {"action": "hangup"})
        note(f"hangup -> {code} {reply}")
        await asyncio.sleep(1)
    await lst.runner.cleanup()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default=os.environ.get("KS_HOST"), required=not os.environ.get("KS_HOST"))
    p.add_argument("--port", type=int, default=2324)
    p.add_argument("--advertise-ip")
    p.add_argument("--listen-port", type=int, default=2325)
    p.add_argument("--name", default="Inbound Call Test")
    p.add_argument("--ring-wait", type=float, default=45.0)
    p.add_argument("--seconds", type=float, default=8.0, help="seconds of quiet tone to send after Answer")
    asyncio.run(run(p.parse_args()))


if __name__ == "__main__":
    main()
