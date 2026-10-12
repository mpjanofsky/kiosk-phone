# Spike tools 

Command-line tools for testing the Java core against a real PBX without a person at a phone. Build with `gradle -q :spike:installDist`; the scripts land in `spike/build/install/spike/bin/`. On the Mac set `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` (the system `java` is a stub).

Settings come from the environment only. Keep passwords in the macOS keychain.

## `spike`: receive pages (PageToWav)
Registers one extension, auto-answers every INVITE (G.722, PCMU or PCMA) and writes each call to `out/page-<time>.wav` as 16 kHz mono PCM16, the KS intercom format.

```bash
SIP_SERVER=<pbx-host> SIP_USER=5099 SIP_PASSWORD="$(security find-generic-password -s ks-sip-spike -w)" spike/build/install/spike/bin/spike
```

Optional: `SIP_AUTH_USER`, `SIP_PORT` (5060), `SIP_LOCAL_PORT` (5070). Runs until killed; the registration expires after 300 s.

## `fake-phone`: place a call/page
Dials a number through the PBX with digest auth, plays a 16 kHz mono WAV, hangs up.

```bash
SIP_SERVER=<pbx-host> PHONE_USER=5098 PHONE_PASSWORD="$(security find-generic-password -s ks-sip-testphone -w)" DIAL='*805099' WAV=samples/speech-test.wav spike/build/install/spike/bin/fake-phone
```

Optional: `SIP_PORT` (5060), `PHONE_LOCAL_PORT` (5072), `HOLD` (s of silence after the WAV), `REC` (WAV path for far-end audio), `CANCEL_AFTER` (s of ringing before the caller cancels; dial a plain extension like `5099`).

## `kiosk-broadcast`: WAV → kiosk
Broadcasts a 16 kHz mono WAV to one kiosk with the Java intercom client, fed in 20 ms chunks like RTP. Check playback with `adb logcat -s IntercomAudio`.

```bash
export KS_INTERCOM_KEY="$(security find-generic-password -s ks-intercom -w)"
KS_HOST=<kiosk-ip> WAV=samples/speech-test.wav spike/build/install/spike/bin/kiosk-broadcast
```

## `page-to-kiosk`: SIP pages → kiosk
Registers one extension and plays every page on one kiosk (`PageBridge`): 200 when the broadcast starts, 486 if the kiosk is busy, 480 otherwise. Same SIP env as `spike`, plus `KS_HOST` and `KS_INTERCOM_KEY` (as above). Optional: `KS_ADMIN_PORT` (2324), `KS_REPLY_PORT`.

```bash
SIP_SERVER=<pbx-host> SIP_USER=5099 SIP_PASSWORD="$(security find-generic-password -s ks-sip-spike -w)" KS_HOST=<kiosk-ip> spike/build/install/spike/bin/page-to-kiosk
```
Then page it with `fake-phone` (`DIAL='*805099'`).
