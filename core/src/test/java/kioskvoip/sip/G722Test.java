package kioskvoip.sip;

import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact against the C original: hashes come from Asterisk's codecs/g722 (SpanDSP) compiled on the Mac and fed the
 * same deterministic inputs (glibc-style LCG, seed 1; decode 4000 random bytes, then encode a noisy triangle wave).
 */
class G722Test {
    private int seed = 1;

    private int next() {
        seed = seed * 1103515245 + 12345;
        return (seed >>> 16) & 0x7fff;
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    @Test
    void matchesSpanDspReference() throws Exception {
        byte[] in = new byte[4000];
        for (int i = 0; i < in.length; i++) in[i] = (byte) next();
        G722.Decoder decoder = new G722.Decoder();
        // Decode in RTP-sized chunks to prove state carries across calls.
        byte[] le = new byte[16000];
        for (int off = 0; off < in.length; off += 160) {
            short[] pcm = decoder.decode(in, off, 160);
            for (int i = 0; i < pcm.length; i++) {
                le[(off * 2 + i) * 2] = (byte) pcm[i];
                le[(off * 2 + i) * 2 + 1] = (byte) (pcm[i] >> 8);
            }
        }
        assertEquals("33831c6629905e93b4a28cbc47cb0bafe73d019cb4bb1f7e797741ae844bfdab", sha256(le));

        short[] pcm = new short[8000];
        for (int i = 0; i < pcm.length; i++) {
            int m = i % 64;
            int tri = m < 32 ? m * 600 - 9600 : (64 - m) * 600 - 9600;
            pcm[i] = (short) (tri + (next() % 2001) - 1000);
        }
        assertEquals("f4d435dc128522f22cf246fc027aaa4bb068c215a1d1bef5e574cc0f4dc3bed8", sha256(new G722.Encoder().encode(pcm)));
    }

    @Test
    void roundTripKeepsA1kHzTone() {
        short[] tone = new short[16000];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) (8000 * Math.sin(2 * Math.PI * 1000 * i / 16000.0));
        short[] back = new G722.Decoder().decode(new G722.Encoder().encode(tone), 0, 8000);
        // Codec delay is 22 samples (two 24-tap QMFs); compare after it.
        double signal = 0, noise = 0;
        for (int i = 1000; i < 15000; i++) {
            signal += (double) tone[i] * tone[i];
            double e = back[i + 22] - tone[i];
            noise += e * e;
        }
        assertTrue(10 * Math.log10(signal / noise) > 25, "SNR above 25 dB");
    }
}
