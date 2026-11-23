#!/usr/bin/env python3
"""Doorbell video spike: ring one kiosk with kind "call" and push a camera's H.264
into the intercom video socket while it rings. Throwaway, like the other probes.

Protocol (read from Kiosk Satellite's intercom_manager.dart / intercom_video.dart):
- POST /api/intercom/call carries "video": {"camera": true}; a kiosk that takes video
  answers {"status": "ringing", "video": true}.
- The caller opens WS /api/intercom/video/<call>?token=..., sends
  {"type": "camera", "on": true}, then binary frames: 9-byte big-endian header
  (flags bit0 = key frame with SPS/PPS, u16 width, u16 height, u32 capture ms)
  + one H.264 access unit in Annex B form.
- The kiosk acks every frame with {"type": "ack", "n": k} and may ask {"type": "key"}.
  At most 6 frames unacknowledged; after that drop until the next key frame.

Key from KS_INTERCOM_KEY only. ffmpeg pulls the stream (copy, no re-encode).
"""
import argparse, asyncio, json, os, struct, sys, time, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aiohttp import ClientSession, ClientTimeout, WSMsgType
import ks_broadcast_test as b

AUD = b"\x00\x00\x00\x01\x09"


def nal_types(au):
    i, out = 0, set()
    while True:
        i = au.find(b"\x00\x00\x01", i)
        if i < 0 or i + 3 >= len(au):
            return out
        out.add(au[i + 3] & 0x1F)
        i += 3


async def access_units(src, width, height):
    """Yields (is_key, annexb_bytes) per access unit from ffmpeg (copy, AUDs inserted)."""
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-loglevel", "error", *(["-rtsp_transport", "tcp"] if src.startswith("rtsp") else []), "-i", src,
        "-an", "-c:v", "copy", "-bsf:v", "h264_mp4toannexb,h264_metadata=aud=insert",
        "-f", "h264", "-", stdout=asyncio.subprocess.PIPE)
    buf = b""
    try:
        while True:
            chunk = await proc.stdout.read(65536)
            if not chunk:
                return
            buf += chunk
            while True:
                start = buf.find(AUD)
                nxt = buf.find(AUD, start + 5) if start >= 0 else -1
                if nxt < 0:
                    break
                au, buf = buf[start:nxt], buf[nxt:]
                t = nal_types(au)
                yield (5 in t and 7 in t and 8 in t), au
    finally:
        if proc.returncode is None:
            proc.kill()


async def run(a):
    key = os.environ.get("KS_INTERCOM_KEY", "")
    if not key:
        raise SystemExit("KS_INTERCOM_KEY is not set")
    cid = str(uuid.uuid4())
    base = f"http://{a.host}:2324"
    async with ClientSession(timeout=ClientTimeout(total=10)) as http:
        async with http.get(base + "/api/intercom/identity") as r:
            ident = await r.json()
        b.log(f"identity: {ident}")
        if ident.get("key") != b.key_digest(key):
            raise SystemExit("key does not match this kiosk")
        port = ident["endpoint"]["port"]
        cb = f"http://{a.host}:{port}"

        async def post(path, body):
            async with http.post(cb + path, json=body,
                                 headers={"Authorization": "Bearer " + b.issue_token(key, cid)}) as r:
                return r.status, await r.json(content_type=None)

        code, reply = await post("/api/intercom/call", {
            "call": cid, "kind": "call",
            "from": {"id": b.SENDER_ID, "name": a.name, "address": a.advertise_ip, "port": 2325,
                     "version": b.VERSION, "tls": False},
            "video": {"camera": True}})
        b.log(f"call -> HTTP {code} {reply}")
        if reply.get("status") not in ("ringing", "auto") or reply.get("video") is not True:
            b.log("kiosk does not take video on this call; stopping")
            if reply.get("status") in ("ringing", "auto"):
                await post("/api/intercom/call/" + cid, {"action": "cancel"})
            return
        ws = await http.ws_connect(f"ws://{a.host}:{port}/api/intercom/video/{cid}",
                                   params={"token": b.issue_token(key, cid)}, heartbeat=10)
        b.log("video ws open")
        await ws.send_json({"type": "camera", "on": True})
        st = {"sent": 0, "acked": 0, "dropped": 0, "keyreq": 0, "wait_key": True, "texts": []}

        async def reader():
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    m = json.loads(msg.data)
                    if m.get("type") == "ack":
                        st["acked"] = max(st["acked"], int(m.get("n", 0)))
                    else:
                        st["texts"].append(msg.data[:80])
                        b.log(f"ws text: {msg.data[:80]}")
                        if m.get("type") == "key":
                            st["keyreq"] += 1
                            st["wait_key"] = True
                else:
                    b.log(f"ws {msg.type}")
        rd = asyncio.create_task(reader())
        t0 = time.monotonic()
        try:
            async for is_key, au in access_units(a.src, a.width, a.height):
                if time.monotonic() - t0 > a.seconds or ws.closed:
                    break
                if st["sent"] - st["acked"] >= 6 or (st["wait_key"] and not is_key):
                    st["dropped"] += 1
                    if st["sent"] - st["acked"] >= 6:
                        st["wait_key"] = True
                    continue
                st["wait_key"] = False
                hdr = struct.pack(">BHHI", 1 if is_key else 0, a.width, a.height,
                                  int((time.monotonic() - t0) * 1000) & 0xFFFFFFFF)
                await ws.send_bytes(hdr + au)
                st["sent"] += 1
                if st["sent"] == 1:
                    b.log(f"first frame sent ({len(au)} bytes, key={is_key}) {time.monotonic() - t0:.2f}s")
        finally:
            b.log(f"sent={st['sent']} acked={st['acked']} dropped={st['dropped']} keyreq={st['keyreq']}")
            rd.cancel()
            try:
                await ws.close()
            except Exception:
                pass
            code, reply = await post("/api/intercom/call/" + cid, {"action": "cancel"})
            b.log(f"cancel -> {code} {reply}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", required=True)
    p.add_argument("--advertise-ip", required=True)
    p.add_argument("--src", required=True, help="anything ffmpeg reads, e.g. http://go2rtc:1984/api/stream.mp4?src=door")
    p.add_argument("--width", type=int, default=720, help="must match the stream")
    p.add_argument("--height", type=int, default=576)
    p.add_argument("--seconds", type=float, default=25.0)
    p.add_argument("--name", default="Front Door")
    asyncio.run(run(p.parse_args()))


if __name__ == "__main__":
    main()
