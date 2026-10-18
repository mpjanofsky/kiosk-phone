# Building, testing and changing the plugin

For people who want to build it from source or change it. If you only want to use it, the
[README](../README.md) is enough.

## What it is made of

- **`core/`**: plain Java with no Android dependencies. SIP and RTP (`kioskvoip.sip`), the G.711 and
  G.722 codecs, the Kiosk Satellite intercom client (`kioskvoip.intercom`) and the bridge that joins
  them (`kioskvoip.bridge`). It runs and is tested on any JVM.
- **`plugin/`**: the thin Kiosk Satellite wrapper (`VoipPlugin`): settings, lifecycle, threads and
  Home Assistant entities. `plugin/build.sh` stages `core/` and `plugin/` together and builds the ZIP.
- **`spike/`**: command-line tools that exercise the core against a real PBX and kiosk without a
  person at a phone: a receiving SIP client, a fake phone, and tools that play a WAV on a kiosk. See
  [spike/README.md](../spike/README.md).
- **`scripts/`**: small Python probes for the intercom protocol, and an installer that pushes a ZIP to a
  kiosk through Remote Admin.
- **`tools/` and `sdk/`**: the plugin builder and SDK interfaces, copied from the Kiosk Satellite
  plugin template (see [NOTICE](../NOTICE)).

The wire details are in [protocol.md](protocol.md) (Kiosk Satellite's intercom, as observed) and
[pbx.md](pbx.md) (what FreePBX does with our SIP).

## Tools you need

- JDK 17 and Gradle. On macOS with Homebrew, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Python 3.
- The Android SDK with build-tools and one platform, found through `ANDROID_HOME` (default
  `~/Library/Android/sdk`). Needed only to build the plugin ZIP.

## Test

```bash
gradle build
```

Runs the unit tests: digest auth, codecs (G.722 is checked bit-exact against the C original), SDP,
RTP, a fake PBX for the SIP flows, and a fake kiosk for the intercom, page and call paths end to end on
loopback.

## Build the plugin

```bash
sh plugin/build.sh                    # development version from the manifest
sh plugin/build.sh --version 1.0.0    # what the release workflow does
```

The ZIP, its `.sha256` and the manifest land in `build/plugin-stage/dist/`. The ZIP must stay under
4 MB.

`scripts/check.sh` runs the tests, builds the plugin and checks the size. `scripts/release.sh 1.0.0`
does the same from a clean tree and tags the commit locally. It pushes nothing.

## Try it on a kiosk

`scripts/ks_install_plugin.py` installs a ZIP through Kiosk Satellite's Remote Admin API. Put the
kiosk address and admin credentials in `.local/LOCAL.md` (kept out of git). Kiosk Satellite must be
restarted first, or it refuses the install ("did not stop cleanly").

Page it without a phone: run the `fake-phone` spike against your PBX and dial the kiosk's extension or
a page group. The kiosk's answer time and first-audio time show up in the plugin's Home Assistant
sensors.

## Releasing

Kiosk Satellite installs from GitHub releases whose assets were uploaded by the repository's own
workflow. To release:

1. Set `version` in `kiosk-satellite-plugin.json` and commit. The tag must be `v` plus that version.
2. Run `scripts/release.sh X.Y.Z`.
3. Push the commit and the tag, then publish a **stable** GitHub release for the tag (not a draft or a
   prerelease). The workflow in `.github/workflows/build.yml` builds the ZIP and attaches the ZIP, its
   checksum and the manifest.

## Limits of the platform

Lifecycle callbacks must return within 3 seconds, so all network work runs on background threads. The
package must stay under 4 MB. Credentials use the `secret` setting type (Kiosk Satellite 2026.10.22+), which reaches the plugin as an ordinary string. The
SDK offers no microphone or intercom API, which is why the plugin talks to the kiosk's own intercom
over `127.0.0.1`.
