package kioskvoip.sip;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** RtpSession's send side: mic audio goes out encoded, silence otherwise. */
class RtpMicTest {
    static final InetAddress LO = InetAddress.getLoopbackAddress();

    private static short[] tone(int samples) {
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) out[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / 16000.0));
        return out;
    }

    private static int peak(short[] pcm) {
        int p = 0;
        for (short s : pcm) p = Math.max(p, Math.abs(s));
        return p;
    }

    private void check(int payload) throws Exception {
        try (DatagramSocket far = new DatagramSocket(0, LO)) {
            far.setSoTimeout(1000);
            RtpSession tx = new RtpSession(LO, new InetSocketAddress(LO, far.getLocalPort()), payload, pcm -> { });
            RtpSession decoder = new RtpSession(LO, new InetSocketAddress(LO, 9), payload, pcm -> { });
            try {
                tx.start();
                DatagramPacket p = new DatagramPacket(new byte[512], 512);
                far.receive(p);
                assertEquals(172, p.getLength());
                assertTrue(peak(decoder.decode(p.getData(), p.getLength())) < 50, "silence before any mic audio");

                tx.writeMic(tone(16000)); // 1 s
                int loud = 0;
                for (int i = 0; i < 80 && loud < 10; i++) {
                    far.receive(p);
                    if (peak(decoder.decode(p.getData(), p.getLength())) > 3000) loud++;
                }
                assertTrue(loud >= 10, "mic audio arrives encoded as payload " + payload + ", loud packets: " + loud);
                assertTrue(tx.micPackets() >= 10);
            } finally {
                tx.close();
                decoder.close();
            }
        }
    }

    @Test void g722() throws Exception { check(Sdp.G722); }
    @Test void pcmu() throws Exception { check(Sdp.PCMU); }
    @Test void pcma() throws Exception { check(Sdp.PCMA); }

    @Test
    void micQueueIsBounded() throws Exception {
        try (DatagramSocket far = new DatagramSocket(0, LO)) {
            RtpSession rtp = new RtpSession(LO, new InetSocketAddress(LO, far.getLocalPort()), Sdp.PCMU, pcm -> { });
            try {
                rtp.writeMic(tone(100_000)); // not started: nothing drains, must not throw or grow
                rtp.writeMic(tone(5));
            } finally {
                rtp.close();
            }
        }
    }
}
