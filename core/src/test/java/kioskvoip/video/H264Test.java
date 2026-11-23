package kioskvoip.video;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class H264Test {
    @Test
    void sizeOfTheDoorbellsSps() {
        // sprop-parameter-sets of the ALP-600 sub stream (av0_1), 720x576 Main.
        byte[] sps = RtspSource.unbase64("Z00AHpY1QWgk03AQEBAg");
        assertEquals(H264.NAL_SPS, H264.type(sps));
        assertArrayEquals(new int[] { 720, 576 }, H264.size(sps));
    }

    @Test
    void sizeWithCroppingAndHighProfile() {
        // x264 1920x1080 High (ffmpeg testsrc): 1088 coded rows cropped by 8.
        byte[] sps = RtspSource.unbase64("Z2QAKKzZQHgCJ+XARAAAAwAEAAADAAg8YMZY");
        assertArrayEquals(new int[] { 1920, 1080 }, H264.size(sps));
    }

    @Test
    void unreadableSpsGivesNull() {
        assertNull(H264.size(new byte[] { 0x67, 0x4d }));
    }

    @Test
    void unescapeDropsEmulationPrevention() {
        assertArrayEquals(new byte[] { 0, 0, 1, 0, 0, 0 }, H264.unescape(new byte[] { 0x67, 0, 0, 3, 1, 0, 0, 3, 0 }, 1));
    }

    @Test
    void keyFrameWithoutInBandParameterSetsGetsThemFromTheSdp() {
        H264.AccessUnit unit = new H264.AccessUnit();
        unit.add(new byte[] { 0x09, 0x10 }); // AUD: dropped
        unit.add(new byte[] { 0x65, 1, 2 });
        byte[] out = unit.annexB(new byte[] { 0x67, 7 }, new byte[] { 0x68, 8 });
        assertArrayEquals(new byte[] { 0, 0, 0, 1, 0x67, 7, 0, 0, 0, 1, 0x68, 8, 0, 0, 0, 1, 0x65, 1, 2 }, out);
    }

    @Test
    void base64RoundTrip() {
        byte[] d = { 1, 2, 3, (byte) 0xff, 0 };
        assertArrayEquals(d, RtspSource.unbase64(RtspSource.base64(d)));
        assertEquals("cGV0ZXI6c2VjcmV0", RtspSource.base64("peter:secret".getBytes()));
    }
}
