package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntercomClientTest {
    static final String KEY = "test-key";
    static final IntercomClient.Peer SELF = new IntercomClient.Peer("0123456789abcdef", "KS VoIP", "127.0.0.1", 39999, "0.1.0");

    FakeKiosk kiosk;
    IntercomClient client;

    @BeforeEach
    void setUp() throws Exception {
        kiosk = new FakeKiosk(KEY);
        client = new IntercomClient("127.0.0.1", kiosk.adminPort(), KEY, SELF, message -> { });
    }

    @AfterEach
    void tearDown() throws Exception { kiosk.close(); }

    @Test
    void tokenMatchesPythonReference() {
        // From scripts/ks_broadcast_test.py's issue_token with the same claims (verified against a real kiosk).
        assertEquals("eyJpbnRlcmNvbSI6ImMxIiwiZnJvbSI6IjAxMjM0NTY3ODlhYmNkZWYiLCJuIjoiYWJhYmFiYWJhYmFiYWJhYmFiYWJhYmFiIiwiZXhwIjoxNzAwMDAwMDAwMDAwfQ==."
            + "xtw8rvE88TjUjnApyeEuKofe6kn5SmPOgzFsyfGpnec=",
            IntercomAuth.token(KEY, "c1", "0123456789abcdef", 1700000000000L, "ab".repeat(12)));
        assertEquals("62af8704", IntercomAuth.digest(KEY));
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> parsed = Json.object("{\"a\":\"x\\\"y\\u00e9\",\"b\":[1,2.5,true,null],\"c\":{\"d\":-3}}");
        assertEquals("x\"yé", parsed.get("a"));
        assertEquals(java.util.Arrays.asList(1L, 2.5, true, null), parsed.get("b"));
        assertEquals("{\"a\":\"x\\\"yé\",\"b\":[1,2.5,true,null],\"c\":{\"d\":-3}}", Json.write(parsed));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
    }

    @Test
    void broadcastStreamsFramesAndHangsUp() throws Exception {
        IntercomClient.Outcome outcome = client.broadcast("Front Desk", null);
        assertTrue(outcome.started(), outcome.detail);
        assertEquals("listening", outcome.status);
        Broadcast b = outcome.broadcast;
        short[] chunk = new short[320]; // 20 ms, like one RTP packet
        for (int i = 0; i < 14; i++) { // 280 ms = 3.5 frames
            for (int j = 0; j < chunk.length; j++) chunk[j] = (short) (i * 1000 + j);
            b.write(chunk);
        }
        b.end();

        assertEquals("call broadcast Front Desk", next());
        assertEquals("text {\"type\":\"talk\",\"on\":true}", next());
        assertEquals("text {\"type\":\"end\"}", next());
        assertEquals("audio closed", next());
        assertEquals("action hangup", next());
        assertEquals(4, kiosk.frames.size());
        for (byte[] f : kiosk.frames) assertEquals(IntercomClient.FRAME_BYTES, f.length);
        byte[] first = kiosk.frames.get(0);
        assertEquals(1, (first[2] & 0xff) | (first[3] << 8)); // sample 1, little-endian
        byte[] last = kiosk.frames.get(3);
        assertEquals(0, last[last.length - 1]); // padded tail
        assertEquals(4, b.framesSent());
        assertFalse(b.endedByKiosk());
    }

    @Test
    void kioskRepliesBecomeStatuses() throws Exception {
        kiosk.callStatus = "busy";
        assertEquals(IntercomClient.BUSY, client.broadcast("x", null).status);
        Thread.sleep(1200); // identity cache
        kiosk.callStatus = "refused";
        IntercomClient.Outcome refused = client.broadcast("x", null);
        assertEquals("refused", refused.status);
        assertFalse(refused.started());
    }

    @Test
    void identityChecksStopBeforeCalling() throws Exception {
        kiosk.dnd = true;
        assertEquals(IntercomClient.DND, client.broadcast("x", null).status);
        Thread.sleep(1200);
        kiosk.dnd = false;
        kiosk.enabled = false;
        assertEquals(IntercomClient.OFF, client.broadcast("x", null).status);
        Thread.sleep(1200);
        kiosk.enabled = true;
        kiosk.reportedKeyDigest = "deadbeef";
        assertEquals(IntercomClient.KEY, client.broadcast("x", null).status);
        Thread.sleep(1200);
        kiosk.reportedKeyDigest = IntercomAuth.digest(KEY);
        kiosk.tls = true;
        assertEquals(IntercomClient.TLS, client.broadcast("x", null).status);
        assertNull(kiosk.events.poll(), "no call should reach the kiosk");
    }

    @Test
    void wrongKeyIsRejectedByKiosk() throws Exception {
        // Digest matches but signature doesn't: the kiosk answers 403.
        IntercomClient wrong = new IntercomClient("127.0.0.1", kiosk.adminPort(), "other-key", SELF, m -> { });
        kiosk.reportedKeyDigest = IntercomAuth.digest("other-key");
        assertEquals(IntercomClient.KEY, wrong.broadcast("x", null).status);
        assertEquals("reject bad signature", next());
    }

    @Test
    void unreachableKiosk() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        IntercomClient gone = new IntercomClient("127.0.0.1", port, KEY, SELF, m -> { });
        assertEquals(IntercomClient.UNREACHABLE, gone.broadcast("x", null).status);
    }

    @Test
    void identityIsReusedWithinASecond() throws Exception {
        client.broadcast("a", null).broadcast.end();
        client.broadcast("b", null).broadcast.end();
        assertEquals(1, kiosk.identityRequests.get());
    }

    @Test
    void dismissOnKioskEndsBroadcast() throws Exception {
        kiosk.dismissAfterFrames = 2;
        CountDownLatch ended = new CountDownLatch(1);
        Broadcast b = client.broadcast("x", ended::countDown).broadcast;
        short[] frame = new short[IntercomClient.FRAME_SAMPLES];
        for (int i = 0; i < 5 && ended.getCount() > 0; i++) {
            b.write(frame);
            Thread.sleep(80);
        }
        assertTrue(ended.await(2, TimeUnit.SECONDS));
        assertTrue(b.endedByKiosk());
        b.end(); // the SIP side ending afterwards is harmless
    }

    private String next() throws InterruptedException {
        String event = kiosk.events.poll(3, TimeUnit.SECONDS);
        assertNotNull(event, "timed out waiting for the fake kiosk");
        return event;
    }
}
