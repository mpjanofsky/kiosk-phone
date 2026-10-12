# PBX side: verified behavior

What FreePBX 17 (Asterisk, chan_pjsip) actually does with our SIP UA. Trust this over general SIP knowledge; add to it when a test proves something new. Test setup: `spike/` (see its README).

## Registration
- REGISTER with MD5 digest, `qop=auth`: 401 challenge, then 200 with the requested expiry (300 s). Verified 2026-10-11.

## Pages (`*80<ext>` intercom, FreePBX "Paging and Intercom")
- INVITE arrives with `Alert-Info: <http://127.0.0.1>;info=Ring Answer` and `Call-Info: <uri>;answer-after=0` (auto-answer hints). We auto-answer regardless.
- Audio starts with a ~0.5 s page beep from the PBX, then the caller's voice. Verified from an iPhone softphone, a desk phone and the fake phone (2026-10-11).
- Codec: the PBX offers ulaw first but accepts our G.722 choice; transcodes when the caller uses something else.
- The caller hanging up → BYE from the PBX.
- When we reject a page with **480**, the caller sees **486 Busy Here** (FreePBX intercom `*80`, verified 2026-10-11 with the kiosk in DND). So the PBX won't tell callers DND from busy; the bridge log does.

## Timers and keepalive
- Endpoint default `rtp_timeout=30`: a call where the PBX receives no RTP for 30 s is hung up. A `recvonly` answer without RTP was cut at exactly 30.0 s. Fix: answer `sendrecv` and send 20 ms silence frames (`RtpSession`). Verified with a 60 s call.
- Session timers are on (`timers=yes`, `timers_sess_expires=1800`): expect a refresh re-INVITE on long calls; handled in code (200 with the same SDP) but **not yet verified live** (needs a page > 15 min).
- `100rel=yes` on the endpoint; not used on calls we receive so far.

## Test extensions (FreePBX)
- 5099 "KS VoIP Spike": the receiving UA (`spike` / PageToWav).
- 5098 "KS VoIP Test Phone": the calling side (`fake-phone`).

## Kiosk-side note: silent pages
A page can be answered (SIP 200), KS can chime and animate, and the Echo can still be silent (seen 2026-10-11 after an Android framework restart). Check by sending a direct `kiosk-broadcast` from the Mac: if that is silent too, the problem is the device, not the plugin or PBX. A reboot fixed it. Intercom volume (`getVolume`/`setVolume` with `channel: intercom`, `percent`) was not the cause.

## Page groups and lockouts
- Page-group members can't be edited with the frogman tools; add an extension to a group in the GUI (Applications → Paging and Intercom → group → Device List, then Apply Config). Extensions created through the API are not added to the default group (80800 All Call) automatically.
- Repeated failed GUI logins get the client IP banned by fail2ban (jail `pbx-gui`, also `recidive`); the ban blocks everything from that IP, including SIP and frogman. Unban from the PBX console: `fail2ban-client set <jail> unbanip <ip>`.
- Ordinary calls to the plugin extension (verified 2026-10-12, fake phone 5098 dialing 5099): the kiosk's Decline (we send 603) and kiosk DND (we reject with 480) both reach the caller as **503 Service Unavailable**; caller CANCEL gives 487 and the CDR shows NO ANSWER. A DND call is rejected at once without ringing.
- A 17.2 min answered call to the plugin extension (2026-10-12, fake phone, G.722 duplex) crossed the 15 min mark with no drop or re-INVITE problem and ended with a clean BYE; 51318 of 51491 mic packets arrived.
- Group pages: dial the page-group number directly (`80800` All Call), not `*80`+group. It runs as a ConfBridge and INVITEs every member at once (5001, 5004, 5099 in the 2026-10-12 test).

## Kiosk settings that refuse or end a page (verified 2026-10-12)
Pages from the fake phone to `*80<ext>` on a real Echo, plugin build 0.2.0, Kiosk Satellite 2026.10.20. Each setting was changed over Remote Admin for the test and restored.
- **Accept announcements** off, **intercom** off, **Lockdown Mode** on, or a broadcast already playing on the kiosk: the page is refused and the caller sees **486 Busy Here** (CDR: NO ANSWER, 0 s). The PBX can't tell these apart; the plugin's status line and log can.
- **Maximum call duration** set to 1 minute: a page held open for 85 s was ended at 59 s and the caller got the hangup (CDR billsec 59, against 84 s with no limit).
- With each setting back to normal, the next page played.
