package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Caller-side kind:"call" against the fake kiosk (ring, Answer/Decline callbacks, duplex audio, hangup). */
class CallTest {
    static final String KEY = IntercomClientTest.KEY;

    FakeKiosk kiosk;
    CallbackServer callbacks;
    IntercomClient client;
    final BlockingQueue<String> heard = new LinkedBlockingQueue<>();
    final BlockingQueue<short[]> mic = new LinkedBlockingQueue<>();

    final Call.Listener listener = new Call.Listener() {
        public void onAnswered() { heard.add("answered"); }
        public void onDeclined() { heard.add("declined"); }
        public void onEnded(String reason) { heard.add("ended " + reason); }
        public void onMic(short[] pcm) { mic.add(pcm); }
    };

    @BeforeEach
    void setUp() throws Exception {
        kiosk = new FakeKiosk(KEY);
        kiosk.callStatus = "ringing";
        callbacks = new CallbackServer(KEY, InetAddress.getLoopbackAddress(), 0, message -> { });
        IntercomClient.Peer self = new IntercomClient.Peer("0123456789abcdef", "KS VoIP", "127.0.0.1", callbacks.port(), "0.1.0");
        client = new IntercomClient("127.0.0.1", kiosk.adminPort(), KEY, self, message -> { });
        client.setCallbackServer(callbacks);
    }

    @AfterEach
    void tearDown() throws Exception {
        callbacks.close();
        kiosk.close();
    }

    private String next(BlockingQueue<String> q) throws Exception { return q.poll(3, TimeUnit.SECONDS); }

    @Test
    void ringsThenAnswerCarriesAudioBothWays() throws Exception {
        kiosk.micFrames = 3;
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertTrue(outcome.started(), outcome.detail);
        assertEquals("ringing", outcome.status);
        assertEquals("call call Front Desk", next(kiosk.events));
        assertNull(heard.poll(200, TimeUnit.MILLISECONDS), "still ringing");
        Call call = outcome.call;
        call.write(new short[1280]); // before the answer: dropped

        kiosk.answer();
        assertEquals("answered", next(heard));
        assertTrue(call.isActive());
        for (int i = 0; i < 3; i++) {
            short[] frame = mic.poll(3, TimeUnit.SECONDS);
            assertNotNull(frame, "mic frame " + i);
            assertEquals(1280, frame.length);
            assertEquals(FakeKiosk.MIC_VALUE, frame[0]);
            assertEquals(FakeKiosk.MIC_VALUE, frame[1279]);
        }
        assertEquals("text {\"type\":\"talk\",\"on\":true}", next(kiosk.events));

        short[] speech = new short[1280];
        java.util.Arrays.fill(speech, (short) 77);
        call.write(speech);
        long deadline = System.currentTimeMillis() + 3000;
        while (kiosk.frames.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, kiosk.frames.size());
        assertEquals(77, (kiosk.frames.get(0)[0] & 0xff));

        call.end();
        String e;
        do { e = next(kiosk.events); } while (e != null && !e.equals("action hangup"));
        assertEquals("action hangup", e);
        assertNull(heard.poll(200, TimeUnit.MILLISECONDS), "our own hangup is not reported as the kiosk ending it");
    }

    @Test
    void declineIsReported() throws Exception {
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertTrue(outcome.started());
        kiosk.decline();
        assertEquals("declined", next(heard));
        assertFalse(outcome.call.isActive());
        assertNull(next2(kiosk.events, "audio"), "no audio socket after a decline");
    }

    private String next2(BlockingQueue<String> q, String contains) throws Exception {
        long end = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < end) {
            String e = q.poll(50, TimeUnit.MILLISECONDS);
            if (e != null && e.contains(contains)) return e;
        }
        return null;
    }

    @Test
    void cancelWhileRingingTellsTheKiosk() throws Exception {
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertEquals("call call Front Desk", next(kiosk.events));
        outcome.call.cancel();
        assertEquals("action cancel", next(kiosk.events));
        assertThrows(java.io.IOException.class, () -> kiosk.answer(), "callback after cancel is not accepted");
        assertNull(heard.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void kioskDismissEndsAnsweredCall() throws Exception {
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        kiosk.answer();
        assertEquals("answered", next(heard));
        kiosk.dismissAfterFrames = 1;
        outcome.call.write(new short[1280]);
        assertEquals("ended kiosk ended the call", next(heard));
        assertFalse(outcome.call.isActive());
    }

    @Test
    void callbackWithBadTokenIsRefused() throws Exception {
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertTrue(outcome.started());
        assertEquals(403, kiosk.callbackStatus("answer", "bogus.token"));
        assertEquals(403, kiosk.callbackStatus("answer", IntercomAuth.token("other-key", kiosk.callId, FakeKiosk.KIOSK_ID)));
        assertEquals(403, kiosk.callbackStatus("answer", IntercomAuth.token(KEY, "another-call", FakeKiosk.KIOSK_ID)));
        assertEquals(403, kiosk.callbackStatus("answer", IntercomAuth.token(KEY, kiosk.callId, FakeKiosk.KIOSK_ID, 1L, "aa")), "expired");
        assertNull(heard.poll(300, TimeUnit.MILLISECONDS), "none of those reached the call");
        String good = IntercomAuth.token(KEY, kiosk.callId, FakeKiosk.KIOSK_ID);
        assertEquals(200, kiosk.callbackStatus("decline", good));
        assertEquals(403, kiosk.callbackStatus("decline", good), "single use");
        assertEquals("declined", next(heard));
    }

    @Test
    void busyKioskReturnsStatusAndNoCall() throws Exception {
        kiosk.callStatus = "busy";
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertFalse(outcome.started());
        assertEquals("busy", outcome.status);
        assertNull(outcome.call);
    }

    @Test
    void callNeedsACallbackServer() {
        client.setCallbackServer(null);
        IntercomClient.Outcome outcome = client.call("Front Desk", listener);
        assertFalse(outcome.started());
        assertEquals(IntercomClient.ERROR, outcome.status);
    }
}
