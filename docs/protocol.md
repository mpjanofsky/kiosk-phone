# KS intercom wire protocol (verified)

Source of truth for this project. "Verified" = observed against a real kiosk; "Docs" = from KS docs / reference code, not yet tested here.
KS version tested: 2026.10.17 (Echo Show 5), 2026-10-09. The protocol is not promised stable by the KS author.

## Discovery
- Peers find each other via mDNS `_kiosk-satellite._tcp`. TXT: `id` (16 hex), `name`, `version`, `port`, `host`. **Verified.**
- KS has no "add peer by IP". A kiosk only calls back (Reply) to a peer it discovered. **Verified.**
- Advertising: `dns-sd -R` (macOS) or `avahi-publish-service` (Linux); the record vanishes when the process exits. A long-lived service should use a library (e.g. python-zeroconf) instead.

## Ports
- `GET /api/intercom/identity` on the admin port (2324) → `{id, name, version, enabled, key, dnd, endpoint: {port, tls}}`. **Verified.**
- Calls go to `endpoint.port` (for example 38303), not 2324. **Verified.** Whether it's fixed across restarts: **untested**; read it per call.
- Identity is rate-limited to 1 request/s per client. (Docs)

## Auth
- `key` in identity = `sha256(key).hexdigest()[:8]`; compare before sending anything signed. **Verified.**
- Token, required on every route except identity; one use, valid 60 s. **Verified.**
  ```
  claims  = {"intercom": call_id, "from": sender_id, "n": token_hex(12), "exp": now_ms + 60000}   # exp in ms
  payload = urlsafe_b64(json.dumps(claims, separators=(",", ":")))   # '=' padding kept
  sig     = urlsafe_b64(hmac_sha256(key="intercom:" + KEY, msg=payload))
  token   = payload + "." + sig
  ```
  HTTP: `Authorization: Bearer <token>`. WebSocket: `?token=<token>`.

## Caller flow (us → kiosk). Verified for broadcast.
1. `POST /api/intercom/call` `{call, kind: "call"|"broadcast", from: {id, name≤80, address, port, version, tls:false}}`
   → `{status}`: `ringing` | `auto` | `listening` | `busy` | `dnd` | `off` | `refused` | `key`. HTTP 403 = wrong key, 409 = TLS mismatch, 426 = plaintext refused. Lockdown answers `dnd`.
2. Open `WS /api/intercom/audio/<call>?token=…` with a fresh token. Send `{"type":"talk","on":true}` **before** any audio.
3. Binary frames: 80 ms PCM16 mono 16 kHz = 2,560 bytes, paced to wall clock. Text frames: `{"type":"talk","on":bool}`, `{"type":"end"}`.
4. End: send `{"type":"end"}`, then `POST /api/intercom/call/<call>` `{action: "hangup"}` (or `cancel` if never accepted).
- Broadcast: chime, then audio, under 1 s delay; wake word and screen unaffected. **Verified.**
- Dismiss on the kiosk card closes the audio WebSocket from the kiosk side. **Verified.**
- Right after the audio WebSocket opens, the kiosk sends `{"type":"talk","on":false}` (its own talk state); ignore it. **Verified** 2026-10-11, KS 2026.10.20.
- `from.version` is not checked against KS versions: our own `0.1.0` works. **Verified** 2026-10-11.
- Java client (`core/.../intercom/`): identity → call → audio socket in ~145 ms over the LAN; frames sent as RTP fills them (no extra pacing) played gap-free for 10 s (logcat `IntercomAudio`). **Verified** 2026-10-11.

## Caller flow with `kind:"call"` (us → kiosk, ring and Answer). Verified 2026-10-11, KS 2026.10.20, `scripts/ks_call_test.py`.
1. `POST /api/intercom/call` with `kind:"call"` → `{"status":"ringing"}` in ~60 ms; the kiosk rings (call card).
2. On Answer the kiosk `POST`s `/api/intercom/call/<call>` `{"action":"answer"}` to `from.address:from.port` with `Authorization: Bearer <token>`. Our listener just returns 200 JSON. (3 s after the ring in the test.)
3. We then open `WS /api/intercom/audio/<call>?token=…` ourselves (same as broadcast) and send `{"type":"talk","on":true}`.
4. Audio is **full duplex at once**: the kiosk sent `{"type":"talk","on":true}` immediately and streamed its mic as 2,560-byte frames (80 ms, PCM16 16 kHz) without us doing anything; 114 frames over ~9 s while we sent a tone the other way. Mic peak 514/32767 in a quiet room (low; may need gain).
5. End: we send `{"type":"end"}` and `POST` `{"action":"hangup"}` → 200.
- **Decline** (verified): kiosk `POST`s `{"action":"decline"}` to the same callback path, ~3 s after the ring in the test; no audio socket.
- **Kiosk-side hangup** (verified): the kiosk sends `{"type":"end"}` on the audio WebSocket and closes it (code 1006); no separate callback was logged.
- **Cancel while ringing** (verified): we `POST` `{"action":"cancel"}` to the kiosk → `200 {"ok":true}`; the ring stops.
- **Plugin path, real PBX call (2026-10-12):** ordinary call 5098 → 5099 rang the Echo, Answer → G.722 duplex, kiosk-side hangup → BYE reached the caller. Speaker audio did **not** leak into the mic (mic stayed at its noise floor, RMS ~6, while the recording played): KS has software echo cancellation on (`audio.software_echo_cancellation` = true).
- **Mic level:** normal speech near the Echo arrives quiet (peak ~3700, RMS 100-200 of 32767; about 30 dB under a healthy level). KS settings `audio.mic_gain_db` = 0 (range -24..+24), capture mode "Raw microphone", noise suppression off. That gain is global (wake word too), so the plugin has its own `micGain` setting (default 8) applied only to call audio.
- Not yet observed: DND/busy with `call`, ring timeout on the kiosk side (~50 s per docs).

## Callee flow (kiosk → us, e.g. Reply). Verified.
1. Kiosk `POST /api/intercom/call` to the peer's advertised port. We reply `{"status":"auto"}`.
2. We `POST /api/intercom/call/<call>` `{action:"answer"}` to the kiosk.
3. The kiosk opens the audio WebSocket **to us** (`/api/intercom/audio/<call>`).
- Other callee actions: `decline`, `missed`. Caller actions: `cancel`, `hangup`.

## Loopback peer (plugin path). Verified 2026-10-11, KS 2026.10.20.
- A peer connecting from 127.0.0.1 on the kiosk itself works: identity via `curl` on the device, then a broadcast through `adb forward` (kiosk sees connections from 127.0.0.1, `from.address` = 127.0.0.1) → `listening`, chime (`ring short`), 16 kHz playback for the full 5 s (logcat `IntercomAudio`). First frame 0.17 s after the call.
- Caveat: the connections came from adbd (shell uid), not KS's own process. A plugin connects from inside KS; nothing seen so far suggests KS filters by source, but the first plugin build should confirm.

## Endpoint port
- `endpoint.port` is **not stable**: 38303 on 2026.10.17, 39603 after updating to 2026.10.20. Always read it from identity before each call. **Verified.**

## Open questions
- Kiosk mic quality (step 1 peaks were low: 1057 and 2243 of 32767).
- Max broadcast length; behavior when two broadcasts overlap; multiple kiosks at once.

## Kiosk-initiated call to a virtual peer. Verified 2026-10-12, KS 2026.10.20.
- A non-KS process that advertises `_kiosk-satellite._tcp` (TXT id, name, version, port, host) and answers `GET /api/intercom/identity` with a matching `key` hash is picked up by the Echo, which polls identity repeatedly.
- Remote API `POST /api/commands/intercomCall` `{kiosk: "<name>"}` or `{id}` (admin Bearer token from `/api/login`) → `{ok, data:{id, kiosk}}`; the Echo then `POST`s `/api/intercom/call` `{call, kind:"call", from:{id, name, address, port, version}}` with a Bearer token to the peer's port. A second `intercomCall` while one is pending → 400 "already in a call".
- Remote API `intercomHangup` / `intercomDismiss` returned ok but did not end a broadcast in a quick test with the screen off (no card); tapping Dismiss on the card ended it (BYE to the PBX within ~4 s). `intercomAnswer`/`intercomDecline` are refused remotely; `intercomStatus` shows state/dnd.
