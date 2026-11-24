# ☎️ Kiosk Phone  

A [Kiosk Satellite](https://kiosksatellite.com) plugin that turns a kiosk into a SIP phone on your PBX. It rings for calls, and it plays pages and announcements, even when nobody has a dashboard open.

![A call rings on the kiosk, is answered, and ends](docs/images/demo.gif)

*A call to the kiosk's extension on an Echo Show 5: ringing, answered, ended.*

![A page playing on the kiosk, with Reply and Dismiss buttons](docs/images/screen-page.png)

*A page playing as an announcement.*

## 📞 Features

- 📞 **A real SIP phone:** The kiosk registers as its own extension, so your PBX treats it like any other phone.
- 🔔 **Calls ring the kiosk:** Answer or Decline on the screen, then talk both ways through the kiosk's speaker and microphone, using the native Kiosk Satellite Intercom.
- 📢 **PBX paging and announcements:** Page the extension or a page group in your PBX and the kiosk plays it through Kiosk Satellite's own intercom as an announcement: same chime, same volume, same Do Not Disturb. Dial your PBX's paging or intercom code for the extension, or add it to a page group, and the kiosk answers on its own.
- 🎥 **Doorbell video:** Calls from a door station can show its camera on the call card while it rings and during the call, through Kiosk Satellite's own intercom video. No SIP video needed: the plugin pulls the camera's H.264 over RTSP itself.
- 🔘 **Call buttons:** Up to three buttons on the call card, for example "Open gate". A button sends DTMF to the caller (RFC 2833 or SIP INFO) or calls a URL, e.g. a Home Assistant webhook. Needs Kiosk Satellite 2026.11.24 or newer.
- 🏠 **Status in Home Assistant:** Whether it is registered, the last call (missed, blocked, declined, answered, with the reason, the audio codec and the time), how fast a page answered, and the call state show up as Kiosk Satellite entities. Home Assistant's history of **Last call** is your call log.

Kiosk Phone doesn't bypass Kiosk Satellite's intercom settings. Audio goes through the intercom, so Do Not Disturb, Lockdown Mode, Accept announcements, the intercom switch, a call already in progress and Maximum call duration all apply as they do for any other intercom call. When the kiosk can't take a page, it stays quiet and the caller is told so.

## 📱 Requirements

| | |
|---|---|
| **You need** | A PBX or SIP provider that speaks standard SIP over UDP, one extension per kiosk, and a kiosk running Kiosk Satellite 2026.10.22 or newer with **Intercom** on (with an intercom key), **Accept announcements** on and Remote Admin on |
| **Tested on** | Echo Show 5 (LineageOS) with FreePBX (Asterisk); G.722, G.711 µ-law and A-law. Doorbell video and call buttons: NS Panels with a FRITZ!Box, an Alphago ALP-600 and an Akuvox R20K, tested by contributors |
| **Should work on** | Other kiosks on Android 7.0 or newer, and other PBXs that follow standard SIP; not tried |

The kiosk must be able to reach the PBX on its SIP port (usually 5060). Outgoing calls from the kiosk are planned, not built.

## 🚀 Install

1. In Kiosk Satellite, open **Settings > Plugin Manager** (or **Plugin Manager** in Remote Admin) and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/mpjanofsky/kiosk-phone`, and choose **Preview**.
3. Review the manifest and this README, then choose **Trust and install**.
4. Set up the kiosk's extension as described below, then enable **Kiosk Phone** with the switch on its row.

## 🛠️ Set up a kiosk

1. On your PBX, create an extension for the kiosk. Each kiosk needs its own extension and secret.
2. In the plugin's row (on the kiosk or in Remote Admin), fill in the [settings](#-settings): PBX address, extension, password and this kiosk's intercom key.
3. Enable the plugin. The status line should say **Registered**.
4. To test, dial the extension from another phone, or page it or its page group. To undo, turn the plugin off; the kiosk unregisters.

## ⚙️ Settings

Set these on each kiosk, in the plugin's row. Every kiosk needs its own extension, with its own secret. Numbers are typed as text.

| Group | Setting | What to enter | Default |
|---|---|---|---|
| SIP account | **PBX address** | Host name or IP of the PBX | |
| SIP account | **PBX port** | SIP UDP port | `5060` |
| SIP account | **Extension** | This kiosk's extension | |
| SIP account | **Auth user** | Only if the PBX login differs from the extension | empty |
| SIP account | **Password** | The extension's SIP secret | |
| SIP account | **Local SIP port** | UDP port the kiosk listens on | `5070` |
| Kiosk intercom | **Intercom key** | This kiosk's Kiosk Satellite intercom key | |
| Kiosk intercom | **KS admin port** | Kiosk Satellite's admin port | `2324` |
| Kiosk intercom | **Microphone gain** | Boosts the mic for calls, 1 (off) to 30. Some kiosk mics are very quiet. | `8` |
| Doorbell video | **Camera stream** | `rtsp://user:password@host:554/path` of an H.264 camera, e.g. a go2rtc stream. Empty = off | empty |
| Doorbell video | **Callers with video** | Comma-separated numbers or names, see [Which callers](#which-callers). Empty = every call | empty |
| Call buttons | **Callers with buttons** | Same format as Callers with video. Empty = every call, but then URL buttons are skipped | empty |
| Call buttons | **Button 1–3** | `Label \| action \| mdi:icon`, see [Call buttons](#-call-buttons). Empty = no button | empty |

## 🎥 Doorbell video

When a call comes from a caller listed under **Callers with video**, the call card shows the door camera's picture while it rings and after you answer. The picture appears almost at once, and the plugin disconnects from the camera when the call ends. Other calls are unchanged.

- Set **Camera stream** to the camera's RTSP address, for example a go2rtc stream. It's a secret setting, so the password in it is masked.
- The kiosk needs **Video calls** on in its intercom settings. It doesn't need a camera of its own.
- The camera must send **H.264** (Baseline, Main or High; avoid B-frames). The plugin doesn't convert video. It connects over RTSP on TCP, with Basic or Digest login.
- If your go2rtc uses `allow_from`, add the kiosk's IP address to it.
- Only the picture comes from the camera. The call's sound still comes over SIP.

How it works is in [docs/protocol.md](docs/protocol.md).

### Which callers

**Callers with video** and **Callers with buttons** take the same list, comma-separated. The plugin compares each entry with the caller's number and name (the name the PBX sends, e.g. `ALP-600 (**620)`), ignoring case.

| Entry | Matches | Doesn't match |
|---|---|---|
| `**620` | `**620` | `**6201`, `**1620` |
| `620` | `620` and `**620` (leading `*` are ignored, for FRITZ!Box internal numbers) | `6200` |
| `0611*` | numbers starting with `0611` | `0612…` |
| `*R20K*` | a name containing `R20K` | |
| `Haustür` | the name `Haustür` | `Haustür hinten` |
| `*` | every caller | |

A `*` is only read as a wildcard when no entry matches exactly. On a number only a trailing `*` is a wildcard, so a FRITZ!Box number like `**620` never matches more than itself.

Up to 0.8.0 an entry matched any part of the caller ID. Entries that relied on that, such as `62` for `**620`, now need a `*`: `62*`.

## 🔘 Call buttons

With Kiosk Satellite 2026.11.24 or newer, the plugin can put up to three buttons on the intercom call card for calls from the callers listed under **Callers with buttons**. Each button is one line: a label, an action and an optional [Material Design icon](https://pictogrammers.com/library/mdi/).

| Action | What it does | Example |
|---|---|---|
| `dtmf:<digits>` | Sends the digits as RFC 2833 / RFC 4733 telephone events in the call's audio | `Open gate \| dtmf:1 \| mdi:gate-open` |
| `info:<digits>` | Sends the digits as SIP INFO (`application/dtmf-relay`), one per digit | `Door \| info:3 \| mdi:door-open` |
| `url:<http(s) URL>` | POST with an empty body, e.g. a Home Assistant webhook (`post:` works too) | `Light \| url:http://<ha-host>:8123/api/webhook/porch \| mdi:lightbulb` |
| `get:<http(s) URL>` | GET request | `Gate \| get:http://<door-station>/cgi-bin/relay?on \| mdi:gate` |

- **DTMF needs an answered call.** Before Answer, a DTMF or INFO button shows an error; URL buttons work at any time, so a gate can be opened without talking.
- **Which DTMF mode?** Use the one your door station expects. `dtmf:` only works if the caller offers `telephone-event` in its SDP; if not, the button fails and you need `info:`.
- Digits are `0-9`, `*`, `#` and `A-D`, up to 16. A URL counts as done on any 2xx answer within 5 s.
- **Devices with a login** (e.g. Akuvox `fcgi/OpenDoor`): put the credentials in the URL, `get:http://user:password@<door-station>/fcgi/OpenDoor?action=OpenDoor&DoorNum=1`. The plugin answers the device's 401 with Basic or Digest auth (one retry). Button settings are plain text, so anyone who can open the plugin settings sees the password: use a dedicated API user where the device allows it.
- **URL buttons need Callers with buttons.** A `url:` or `get:` button usually opens something, so with **Callers with buttons** left empty the plugin skips it and says so in the log. DTMF and INFO buttons only reach the caller and show on every call.
- **Use a dedicated API user** on the door station for URL buttons where it allows one, with only the rights the button needs.
- The button shows a check mark when the action worked and an error when it didn't.

Tested with an Alphago ALP-600 (SIP INFO) on a FRITZ!Box, and an Akuvox R20K (DTMF, SIP INFO and a URL).

## 🔒 Privacy and security

- **Secrets are masked.** The SIP password, the intercom key and the camera stream use Kiosk Satellite's secret setting type (2026.10.22 or newer), so Plugin Manager and Remote Admin hide them and only let you replace them. Call button settings are plain text, so a password in a button URL is visible to anyone who can open the plugin settings. Use a dedicated extension and keep the admin port on a trusted network.
- **Nothing leaves your network** except the SIP traffic to your own PBX, and the camera and button URLs you set. The plugin has no analytics and contacts no other service.
- **SIP and audio aren't encrypted** (UDP and RTP). Keep them on your LAN or a VPN.
- **The plugin runs inside Kiosk Satellite** with its permissions, like every plugin. Review the code before you trust it.

## 🧰 Troubleshooting

The status line shows in the plugin's row in Plugin Manager, on the kiosk or in Remote Admin.

| What you see | What to check |
|---|---|
| Status says `Not registered` | PBX address, port, extension and password. Is the kiosk allowed to reach the PBX on that port? |
| Status says `No answer from <address>:<port>` | The PBX never replied. Check **PBX host** and **PBX port**, that the kiosk can reach the PBX, and that the extension exists on the PBX (on a FRITZ!Box, set the kiosk up as an IP phone). |
| Status says `No intercom key configured` | Fill in **Intercom key** with the key from Kiosk Satellite. |
| Registered, but pages are silent | Intercom on? **Accept announcements** on? Kiosk not in Do Not Disturb or Lockdown Mode? Is the key right? |
| The kiosk chimes and shows the page but plays no sound | Try a plain Kiosk Satellite intercom broadcast. If that is silent too, the kiosk's audio is stuck, not the plugin. A reboot fixed it for us. |
| Caller is turned away (SIP 480 or 486) | Kiosk Satellite's intercom refused the call. The **Last call** entity says why, for example `blocked: Do Not Disturb`, `blocked: intercom off`, `blocked: intercom key mismatch` or `busy`. The status line only shows registration. Some PBXs (FreePBX included) show the caller the same busy or unavailable message for every reason, so look at **Last call**. See [docs/pbx.md](docs/pbx.md). |
| The other side can barely hear you on a call | Raise **Microphone gain**. |


## 🙏 Credits

- SIP code adapted from [meiser79/kiosk-satellite-sip-phone](https://github.com/meiser79/kiosk-satellite-sip-phone) (Apache-2.0).
- G.722 codec ported from SpanDSP by Steve Underwood (public domain), via [Asterisk](https://github.com/asterisk/asterisk).
- Plugin build tools and release workflow follow the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) (Apache-2.0).

Details are in [NOTICE](NOTICE) and [licenses/](licenses/).

*Not affiliated with or endorsed by the Kiosk Satellite project.*

## 💬 Feedback and contributing

Ideas or problems? Please [open an issue](https://github.com/mpjanofsky/kiosk-phone/issues). For developers: [building and changing it](docs/DEVELOPING.md), the [Kiosk Satellite intercom protocol](docs/protocol.md) as we observed it, and [PBX behavior](docs/pbx.md).

🤖 Built with a lot of help from Claude, who worked with me on most of the design, code, tests and docs.

Code is Apache-2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
