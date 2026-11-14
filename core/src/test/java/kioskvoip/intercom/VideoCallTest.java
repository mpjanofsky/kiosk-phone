package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kioskvoip.video.RtspSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A call that offers video: the offer, the video socket, the frame header, key-frame gating and flow control. */
class VideoCallTest {
    static final String KEY = IntercomClientTest.KEY;

    FakeKiosk kiosk;
    CallbackServer callbacks;
    IntercomClient client;

    final Call.Listener listener = new Call.Listener() {
        public void onAnswered() { }
        public void onDeclined() { }
        public void onEnded(String reason) { }
        public void onMic(short[] pcm) { }
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

    static RtspSource.Frame frame(boolean key, int fill, int length) {
        byte[] d = new byte[length];
        d[3] = 1;
        d[4] = (byte) (key ? 0x65 : 0x41);
        for (int i = 5; i < length; i++) d[i] = (byte) fill;
        return new RtspSource.Frame(key, 720, 576, d, System.currentTimeMillis());
    }

    private String next() throws Exception { return kiosk.events.poll(3, TimeUnit.SECONDS); }

    private void awaitFrames(int n) throws Exception {
        for (int i = 0; i < 100 && kiosk.videoFrames.size() < n; i++) Thread.sleep(20);
    }

    @Test
    void offersVideoAndSendsHeaderedFramesFromTheFirstKeyFrame() throws Exception {
        kiosk.takesVideo = true;
        IntercomClient.Outcome outcome = client.call("Haustuer", listener, true);
        assertTrue(outcome.started(), outcome.detail);
        assertEquals("call call Haustuer", next());
        assertEquals(Map.of("camera", true), kiosk.lastVideoOffer);
        Call call = outcome.call;
        assertTrue(call.videoAccepted());

        VideoLink link = call.openVideo(null);
        assertNotNull(link);
        assertEquals("video open", next());
        assertEquals("video text {\"type\":\"camera\",\"on\":true}", next());

        link.onFrame(frame(false, 1, 100)); // before any key frame: useless to a decoder, dropped
        link.onFrame(frame(true, 2, 70_000)); // bigger than a 16-bit WebSocket length
        link.onFrame(frame(false, 3, 200));
        awaitFrames(2);
        assertEquals(2, kiosk.videoFrames.size());
        byte[] key = kiosk.videoFrames.get(0), p = kiosk.videoFrames.get(1);
        assertEquals(1, key[0], "key flag");
        assertEquals(720, ((key[1] & 0xff) << 8) | (key[2] & 0xff));
        assertEquals(576, ((key[3] & 0xff) << 8) | (key[4] & 0xff));
        assertEquals(VideoLink.HEADER + 70_000, key.length);
        assertEquals(0x65, key[VideoLink.HEADER + 4]);
        assertEquals(0, p[0]);
        assertEquals(VideoLink.HEADER + 200, p.length);

        call.cancel();
        String e;
        boolean cameraOff = false, closed = false, cancelled = false;
        while ((e = next()) != null && !(cameraOff && closed && cancelled)) {
            if (e.equals("video text {\"type\":\"camera\",\"on\":false}")) cameraOff = true;
            if (e.equals("video closed")) closed = true;
            if (e.equals("action cancel")) cancelled = true;
        }
        assertTrue(cameraOff && closed && cancelled, "camera off, socket closed and call cancelled");
        assertNull(call.openVideo(null), "no video after the call");
    }

    @Test
    void kioskWithoutVideoGetsNoSocket() throws Exception {
        kiosk.takesVideo = false;
        IntercomClient.Outcome outcome = client.call("Haustuer", listener, true);
        assertTrue(outcome.started());
        assertFalse(outcome.call.videoAccepted());
        assertNull(outcome.call.openVideo(null));
        outcome.call.cancel();
    }

    @Test
    void plainCallsOfferNoVideo() throws Exception {
        kiosk.takesVideo = true;
        IntercomClient.Outcome outcome = client.call("Peter", listener);
        assertTrue(outcome.started());
        assertNull(kiosk.lastVideoOffer);
        assertFalse(outcome.call.videoAccepted());
        outcome.call.cancel();
    }

    @Test
    void stalledKioskStopsAtSixThenWaitsForAKeyFrame() throws Exception {
        kiosk.takesVideo = true;
        kiosk.ackVideo = false;
        Call call = client.call("Haustuer", listener, true).call;
        VideoLink link = call.openVideo(null);
        link.onFrame(frame(true, 1, 50));
        for (int i = 0; i < 10; i++) { link.onFrame(frame(false, 2, 50)); Thread.sleep(5); }
        awaitFrames(VideoLink.MAX_IN_FLIGHT);
        Thread.sleep(200);
        assertEquals(VideoLink.MAX_IN_FLIGHT, kiosk.videoFrames.size(), "no more than six unacknowledged");
        assertTrue(link.framesDropped() >= 5);
        call.cancel();
    }

    @Test
    void keyRequestHoldsBackUntilTheNextKeyFrame() throws Exception {
        kiosk.takesVideo = true;
        Call call = client.call("Haustuer", listener, true).call;
        VideoLink link = call.openVideo(null);
        link.onFrame(frame(true, 1, 50));
        awaitFrames(1);
        kiosk.requestKey();
        Thread.sleep(100);
        link.onFrame(frame(false, 2, 50)); // held back
        link.onFrame(frame(true, 3, 50));
        awaitFrames(2);
        Thread.sleep(100);
        assertEquals(2, kiosk.videoFrames.size());
        assertEquals(1, kiosk.videoFrames.get(1)[0]);
        call.cancel();
    }

    @Test
    void recentKeyFrameShowsAtOnceThenWaitsForALiveOne() throws Exception {
        kiosk.takesVideo = true;
        Call call = client.call("Haustuer", listener, true).call;
        VideoLink link = call.openVideo(frame(true, 9, 60));
        awaitFrames(1);
        assertEquals(1, kiosk.videoFrames.size(), "the cached still goes out straight away");
        link.onFrame(frame(false, 2, 50)); // follows a newer key frame we never sent: dropped
        link.onFrame(frame(true, 3, 50));
        link.onFrame(frame(false, 4, 50));
        awaitFrames(3);
        Thread.sleep(100);
        assertEquals(3, kiosk.videoFrames.size());
        assertEquals(3, kiosk.videoFrames.get(1)[VideoLink.HEADER + 5]);
        call.cancel();
    }
}
