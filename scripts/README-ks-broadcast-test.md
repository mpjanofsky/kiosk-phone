# ks_broadcast_test.py: KS intercom broadcast test

One-way intercom broadcast to a kiosk (`<kiosk-ip>:2324`). Throwaway test for chime, audio quality, latency and DND behavior.

## Kiosk checklist (verify on the Echo)
Intercom enabled; Remote management and "Find other kiosks" on; same key; "Accept announcements" on; not in Do Not Disturb or Lockdown Mode; max call duration not below the test length.

## Setup (Mac or Linux)
```bash
python3 -m venv .venv && .venv/bin/pip install aiohttp
read -rs KS_INTERCOM_KEY && export KS_INTERCOM_KEY      # key is not echoed or saved to history
# Mac option: export KS_INTERCOM_KEY="$(security find-generic-password -s ks-intercom -w)"
```
On macOS, allow incoming connections for Python if prompted. The firewall can block the listener port (default 2325), and the kiosk's Reply/Dismiss callbacks won't arrive.

## Run
```bash
.venv/bin/python scripts/ks_broadcast_test.py                      # 5 s 440 Hz tone
.venv/bin/python scripts/ks_broadcast_test.py --wav samples/speech-test.wav     # speech tests intelligibility better than a tone
.venv/bin/python scripts/ks_broadcast_test.py --repeat 20 --interval 30 --linger 0   # soak run
```
Reply test (kiosk calls back, two-way audio): add `--mdns` so the script advertises itself as a KS peer (`_kiosk-satellite._tcp`) while it runs. KS only replies to discovered peers. Use a long clip (`--wav samples/speech-long.wav`), press Reply on the card, and the script answers and plays the same audio back for `--reply-seconds` (default 15). The advertisement is removed when the script exits. While it runs, every kiosk on the LAN lists the fake peer.

Options: `--mdns`, `--mdns-wait`, `--reply-seconds`, `--host`, `--port`, `--advertise-ip` (override the auto-detected LAN IP), `--listen-port`, `--name`, `--linger` (seconds to keep the listener up after a single run so you can press Reply/Dismiss on the card; default 15).

WAV files that aren't 16 kHz mono PCM16 are converted with ffmpeg if installed.

## NAS / Unraid soak (Docker, host networking, key passed by name only)
```bash
read -rs KS_INTERCOM_KEY && export KS_INTERCOM_KEY
docker run --rm --network host -e KS_INTERCOM_KEY -v "$PWD":/app -w /app python:3.12-slim \
  sh -c "pip install -q aiohttp && python scripts/ks_broadcast_test.py --repeat 100 --interval 60 --linger 0"
```

## Exit codes
0 ok, 2 config (e.g. key missing), 3 host unreachable, 4 wrong key, 5 intercom off, 6 DND/Lockdown, 7 busy, 8 TLS required, 9 refused, 10 other.

## Please report back
Chime heard? Voice intelligible? Approximate delay? Wake word OK afterward? Screensaver or screen-wake oddities? What did the listener log when you pressed Reply or Dismiss?

## Findings (2026-10-09)
- Broadcast works: chime, then clear speech, under 1 s delay, wake word and screen unaffected. Dismiss closes the audio socket from the kiosk side.
- Calls use `endpoint.port` from identity (38303 here), not the admin port 2324.
- Reply needs the sender to be mDNS-discovered. Flow: kiosk posts `/api/intercom/call`; we reply `auto`, then POST `action=answer` to the kiosk; the kiosk opens the audio WebSocket to us.
