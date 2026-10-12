// SPDX-License-Identifier: Apache-2.0
package kioskvoip.spike;

import kioskvoip.intercom.Broadcast;
import kioskvoip.intercom.IntercomClient;
import kioskvoip.sip.FakePhone;

/** Broadcasts a 16 kHz mono WAV to one kiosk with the Java intercom client, paced like RTP (20 ms chunks). Env: WAV + KioskSetup. */
public final class KioskBroadcast {
    public static void main(String[] args) throws Exception {
        short[] pcm = FakePhone.readWav(KioskSetup.env("WAV", null));
        IntercomClient client = KioskSetup.client("broadcast-test");
        long t0 = System.nanoTime();
        IntercomClient.Outcome outcome = client.broadcast(KioskSetup.env("KS_CALLER", "KS VoIP test"), () -> KioskSetup.event("kiosk ended it"));
        KioskSetup.event(String.format("status %s (%s) after %d ms", outcome.status, outcome.detail, (System.nanoTime() - t0) / 1_000_000));
        if (!outcome.started()) System.exit(1);
        Broadcast b = outcome.broadcast;
        long start = System.nanoTime();
        for (int i = 0; i * 320 < pcm.length && !b.endedByKiosk(); i++) {
            long due = start + i * 20_000_000L;
            long wait = due - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            short[] chunk = new short[Math.min(320, pcm.length - i * 320)];
            System.arraycopy(pcm, i * 320, chunk, 0, chunk.length);
            b.write(chunk);
        }
        b.end();
        Thread.sleep(1500); // let the sender finish and hang up
    }
}
