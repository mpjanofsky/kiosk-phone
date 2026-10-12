package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MicGainTest {
    @Test
    void scalesQuietAudioLinearly() {
        short[] out = PageBridge.applyGain(new short[] {0, 100, -100, 1000}, 8);
        assertArrayEquals(new short[] {0, 800, -800, 8000}, out);
    }

    @Test
    void compressesPeaksWithoutWrapping() {
        short[] out = PageBridge.applyGain(new short[] {3731, -3731, 32767, -32768}, 8);
        assertTrue(out[0] > 24000 && out[0] < 32767, "loud peak is compressed, not clipped hard: " + out[0]);
        assertEquals(-out[0], out[1]);
        assertEquals(32767, out[2]);
        assertEquals(-32767, out[3]);
    }
}
