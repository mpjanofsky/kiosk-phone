package kioskvoip.sip;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives SipClient against a fake PBX on loopback. */
class SipClientTest {
    static final InetAddress LO = InetAddress.getLoopbackAddress();
    static final String REALM = "asterisk", NONCE = "abc123", PASSWORD = "test-secret";

    DatagramSocket pbx, pbxRtp;
    SipClient client;
    int clientPort;
    final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    final AtomicInteger samples = new AtomicInteger();
    volatile SipClient.Answer answer;

    @BeforeEach
    void setUp() throws Exception {
        pbx = new DatagramSocket(0, LO);
        pbx.setSoTimeout(3000);
        pbxRtp = new DatagramSocket(0, LO);
        try (DatagramSocket free = new DatagramSocket(0, LO)) { clientPort = free.getLocalPort(); }
        answer = SipClient.Answer.accept(pcm -> samples.addAndGet(pcm.length));
        client = new SipClient(new SipClient.Config("127.0.0.1", pbx.getLocalPort(), "1001", "", PASSWORD, clientPort, 300),
            new SipClient.Listener() {
                public void onRegistration(boolean ok, String detail) { events.add("reg " + ok); }
                public SipClient.Answer onInvite(String caller, String codec) { events.add("invite " + codec); return answer; }
                public void onCallStarted(String caller) { events.add("started"); }
                public void onCallEnded(String reason, long packets, long dropped) { events.add("ended " + packets); }
                public void log(String message) { }
            });
        client.start();
        register();
    }

    @AfterEach
    void tearDown() {
        client.stop();
        pbx.close();
        pbxRtp.close();
    }

    private SipMessage receive() throws Exception {
        while (true) {
            DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
            pbx.receive(p);
            SipMessage m = SipMessage.parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            if (m != null && !"REGISTER".equals(m.cseqMethod()) || m != null && m.isRequest()) return m;
        }
    }

    private void send(String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        pbx.send(new DatagramPacket(data, data.length, new InetSocketAddress(LO, clientPort)));
    }

    private static String reply(SipMessage req, String status, String extra) {
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(status).append("\r\n");
        for (String via : req.all("via")) sb.append("Via: ").append(via).append("\r\n");
        return sb.append("From: ").append(req.header("from")).append("\r\nTo: ").append(req.header("to"))
            .append(";tag=pbx\r\nCall-ID: ").append(req.header("call-id")).append("\r\nCSeq: ").append(req.header("cseq"))
            .append("\r\n").append(extra).append("Content-Length: 0\r\n\r\n").toString();
    }

    private void register() throws Exception {
        SipMessage first = receive();
        assertEquals("REGISTER", first.method());
        assertNull(first.header("authorization"));
        send(reply(first, "401 Unauthorized",
            "WWW-Authenticate: Digest realm=\"" + REALM + "\", nonce=\"" + NONCE + "\", algorithm=MD5, qop=\"auth\"\r\n"));
        SipMessage second = receive();
        Map<String, String> auth = SipDigest.parseParams(second.header("authorization"));
        assertEquals(SipDigest.response("1001", REALM, PASSWORD, "REGISTER", auth.get("uri"), NONCE, "auth",
            auth.get("nc"), auth.get("cnonce")), auth.get("response"));
        send(reply(second, "200 OK", "Expires: 300\r\n"));
        assertEquals("reg true", events.poll(3, TimeUnit.SECONDS));
    }

    private String invite(String callId, String codecs) {
        String sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio "
            + pbxRtp.getLocalPort() + " RTP/AVP " + codecs + "\r\n";
        return "INVITE sip:1001@127.0.0.1:" + clientPort + " SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP 127.0.0.1:" + pbx.getLocalPort() + ";branch=z9hG4bK" + callId + "\r\n"
            + "From: \"Page\" <sip:900@127.0.0.1>;tag=from" + callId + "\r\n"
            + "To: <sip:1001@127.0.0.1>\r\nCall-ID: " + callId + "\r\nCSeq: 1 INVITE\r\n"
            + "Contact: <sip:900@127.0.0.1:" + pbx.getLocalPort() + ">\r\n"
            + "Content-Type: application/sdp\r\nContent-Length: " + sdp.length() + "\r\n\r\n" + sdp;
    }

    private SipMessage finalResponse() throws Exception {
        while (true) {
            SipMessage m = receive();
            if (!m.isRequest() && m.status() >= 200) return m;
        }
    }

    @Test
    void answersPageReceivesRtpAndEndsOnBye() throws Exception {
        send(invite("c1", "0 8"));
        assertEquals("invite PCMU", events.poll(3, TimeUnit.SECONDS));
        SipMessage ok = finalResponse();
        assertEquals(200, ok.status());
        Sdp.Offer ours = Sdp.parseOffer(ok.body, new int[] {Sdp.PCMU});
        assertTrue(ok.body.contains("a=sendrecv"));
        assertEquals("started", events.poll(3, TimeUnit.SECONDS));
        send("ACK sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1;branch=z9hG4bKack\r\nFrom: <sip:900@127.0.0.1>;tag=fromc1\r\n"
            + "To: " + ok.header("to") + "\r\nCall-ID: c1\r\nCSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n");

        pbxRtp.setSoTimeout(1000);
        DatagramPacket silence = new DatagramPacket(new byte[512], 512);
        pbxRtp.receive(silence);
        assertEquals(172, silence.getLength(), "20 ms PCMU silence keeps PBX RTP timeouts happy");
        assertEquals((byte) 0xff, silence.getData()[12]);

        byte[] rtp = new byte[12 + 160];
        rtp[0] = (byte) 0x80;
        for (int i = 0; i < 5; i++) {
            rtp[3] = (byte) i;
            pbxRtp.send(new DatagramPacket(rtp, rtp.length, new InetSocketAddress(LO, ours.port)));
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (samples.get() < 5 * 320 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(5 * 320, samples.get(), "5 packets of 160 samples upsampled to 16 kHz");

        send("BYE sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbx.getLocalPort() + ";branch=z9hG4bKbye\r\n"
            + "From: <sip:900@127.0.0.1>;tag=fromc1\r\nTo: " + ok.header("to") + "\r\nCall-ID: c1\r\nCSeq: 2 BYE\r\nContent-Length: 0\r\n\r\n");
        SipMessage byeOk = finalResponse();
        assertEquals("BYE", byeOk.cseqMethod());
        assertEquals(200, byeOk.status());
        assertEquals("ended 5", events.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void rejectsWithListenerCode() throws Exception {
        answer = SipClient.Answer.busy();
        send(invite("c2", "0"));
        assertEquals(486, finalResponse().status());
        answer = SipClient.Answer.unavailable();
        send(invite("c3", "8"));
        assertEquals(480, finalResponse().status());
    }

    @Test
    void prefersG722() throws Exception {
        send(invite("c6", "0 8 9"));
        assertEquals("invite G722", events.poll(3, TimeUnit.SECONDS));
        SipMessage ok = finalResponse();
        assertTrue(ok.body.contains("m=audio ") && ok.body.contains(" RTP/AVP 9\n"), ok.body);
    }

    @Test
    void rejectsOfferWithoutCommonCodec() throws Exception {
        send(invite("c4", "18"));
        assertEquals(488, finalResponse().status());
        assertNull(events.poll(200, TimeUnit.MILLISECONDS), "listener not asked");
    }

    @Test
    void retransmitsOkUntilAck() throws Exception {
        send(invite("c5", "0"));
        assertEquals(200, finalResponse().status());
        assertEquals(200, finalResponse().status(), "200 OK resent while no ACK");
    }

    private SipMessage nextResponse() throws Exception {
        while (true) {
            SipMessage m = receive();
            if (!m.isRequest() && m.status() != 100) return m;
        }
    }

    private String cancel(String callId) {
        return "CANCEL sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbx.getLocalPort() + ";branch=z9hG4bK" + callId
            + "\r\nFrom: \"Page\" <sip:900@127.0.0.1>;tag=from" + callId + "\r\nTo: <sip:1001@127.0.0.1>\r\nCall-ID: " + callId
            + "\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n";
    }

    private final BlockingQueue<String> ringEvents = new LinkedBlockingQueue<>();

    private void ringOnNextInvite() {
        answer = SipClient.Answer.ring(reason -> ringEvents.add("cancelled " + reason));
    }

    @Test
    void ringsThenAccepts() throws Exception {
        ringOnNextInvite();
        send(invite("r1", "0"));
        SipMessage ringing = nextResponse();
        assertEquals(180, ringing.status());
        String toTag = ringing.header("to");
        assertTrue(toTag.contains("tag="));
        assertTrue(client.isRinging());

        assertTrue(client.acceptRinging(pcm -> { }));
        SipMessage ok = finalResponse();
        assertEquals(200, ok.status());
        assertEquals(toTag, ok.header("to"), "same To tag as the 180");
        assertFalse(client.isRinging());
        assertFalse(client.acceptRinging(pcm -> { }), "only once");
    }

    @Test
    void ringsThenDeclines() throws Exception {
        ringOnNextInvite();
        send(invite("r2", "0"));
        assertEquals(180, nextResponse().status());
        assertTrue(client.declineRinging(603, "Decline"));
        assertEquals(603, finalResponse().status());
        assertFalse(client.declineRinging(603, "Decline"));
    }

    @Test
    void cancelWhileRingingAnswers487And200() throws Exception {
        ringOnNextInvite();
        send(invite("r3", "0"));
        assertEquals(180, nextResponse().status());
        send(cancel("r3"));
        SipMessage a = nextResponse(), b = nextResponse();
        SipMessage cancelOk = "CANCEL".equals(a.cseqMethod()) ? a : b, inviteFinal = "INVITE".equals(a.cseqMethod()) ? a : b;
        assertEquals(200, cancelOk.status());
        assertEquals(487, inviteFinal.status());
        assertEquals("cancelled Caller cancelled", ringEvents.poll(3, TimeUnit.SECONDS));
        assertFalse(client.isRinging());
        assertFalse(client.acceptRinging(pcm -> { }));
    }

    @Test
    void ringTimeoutAnswers480() throws Exception {
        client.setRingTimeoutMs(300);
        ringOnNextInvite();
        send(invite("r4", "0"));
        assertEquals(180, nextResponse().status());
        assertEquals(480, finalResponse().status());
        assertEquals("cancelled Ring timeout", ringEvents.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void secondInviteWhileRingingIsBusyAndRetransmitRepeats180() throws Exception {
        ringOnNextInvite();
        send(invite("r5", "0"));
        assertEquals(180, nextResponse().status());
        send(invite("r5", "0"));
        assertEquals(180, nextResponse().status(), "retransmitted INVITE gets 180 again");
        send(invite("r6", "0"));
        assertEquals(486, finalResponse().status());
        assertTrue(client.isRinging());
    }
}
