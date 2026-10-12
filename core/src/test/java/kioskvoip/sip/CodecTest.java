package kioskvoip.sip;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CodecTest {
    @Test
    void g711RoundTripsWithinQuantization() {
        for (int x = -32768; x <= 32767; x += 7) {
            short s = (short) x;
            int mu = G711.muToLinear(G711.linearToMu(s));
            int a = G711.aToLinear(G711.linearToA(s));
            // Both laws keep about 4 % relative error; allow a small absolute floor near zero.
            assertTrue(Math.abs(mu - x) <= Math.max(16, Math.abs(x) / 16), "mu " + x + " -> " + mu);
            assertTrue(Math.abs(a - x) <= Math.max(16, Math.abs(x) / 16), "A " + x + " -> " + a);
        }
    }

    @Test
    void g711KnownValues() {
        assertEquals((byte) 0xff, G711.linearToMu((short) 0));
        assertEquals((byte) 0xd5, G711.linearToA((short) 0));
        assertEquals(-32124, G711.muToLinear((byte) 0x00));
        assertEquals(32124, G711.muToLinear((byte) 0x80));
    }

    @Test
    void rtpDecodeUpsamplesAndSkipsHeaderExtension() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (RtpSession rx = new RtpSession(lo, new java.net.InetSocketAddress(lo, 9), Sdp.PCMU, pcm -> { })) {
            byte[] p = new byte[12 + 8 + 4];
            p[0] = (byte) 0x90; // V=2, X=1
            p[1] = 0;
            p[14] = 0; p[15] = 1; // one extension word
            for (int i = 20; i < 24; i++) p[i] = G711.linearToMu((short) 1000);
            short[] pcm = rx.decode(p, p.length);
            assertEquals(8, pcm.length);
            assertEquals(G711.muToLinear(G711.linearToMu((short) 1000)), pcm[7]);
            p[1] = 8; // wrong payload type
            assertNull(rx.decode(p, p.length));
        }
    }

    @Test
    void sdpPicksPreferredCodecAndMediaAddress() throws Exception {
        String offer = "v=0\r\no=- 1 1 IN IP4 192.0.2.1\r\ns=-\r\nc=IN IP4 192.0.2.1\r\nt=0 0\r\n"
            + "m=audio 4000 RTP/AVP 9 8 0 101\r\nc=IN IP4 192.0.2.2\r\na=rtpmap:101 telephone-event/8000\r\n";
        Sdp.Offer o = Sdp.parseOffer(offer, new int[] {Sdp.PCMU, Sdp.PCMA});
        assertEquals(Sdp.PCMU, o.payload);
        assertEquals(4000, o.port);
        assertEquals("192.0.2.2", o.address.getHostAddress());
        assertThrows(IllegalArgumentException.class,
            () -> Sdp.parseOffer(offer.replace("9 8 0 101", "18 101"), new int[] {Sdp.PCMU}));
    }

    @Test
    void digestMatchesRfc2617Example() {
        // RFC 2617 section 3.5.
        assertEquals("6629fae49393a05397450978507c4ef1", SipDigest.response("Mufasa", "testrealm@host.com",
            "Circle Of Life", "GET", "/dir/index.html", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "00000001", "0a4f113b"));
    }
}
