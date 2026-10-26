package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kioskvoip.bridge.PageBridge;
import kioskvoip.sip.SipClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Whole inbound path on loopback: fake PBX (UDP) → SipClient → PageBridge → IntercomClient → fake kiosk. */
class CallBridgeTest {
    static final InetAddress LO = InetAddress.getLoopbackAddress();

    FakeKiosk kiosk;
    CallbackServer callbacks;
    DatagramSocket pbx, pbxRtp;
    SipClient sip;
    int sipPort;
    final BlockingQueue<String> bridgeEvents = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        kiosk = new FakeKiosk(IntercomClientTest.KEY);
        kiosk.callStatus = "ringing";
        callbacks = new CallbackServer(IntercomClientTest.KEY, LO, 0, m -> { });
        IntercomClient.Peer self = new IntercomClient.Peer("0123456789abcdef", "KS VoIP", "127.0.0.1", callbacks.port(), "0.1.0");
        IntercomClient intercom = new IntercomClient("127.0.0.1", kiosk.adminPort(), IntercomClientTest.KEY, self, m -> { });
        intercom.setCallbackServer(callbacks);
        PageBridge bridge = new PageBridge(intercom, new PageBridge.Events() {
            public void onRegistration(boolean registered, String detail) { }
            public void onPage(String caller, String status, int sipCode, String codec, String detail) { }
            public void onPageEnded(String caller, String reason) { bridgeEvents.add("page ended"); }
            public void onCall(String caller, String state, String detail) { bridgeEvents.add(state); }
            public void log(String message) { }
        });
        pbx = new DatagramSocket(0, LO);
        pbx.setSoTimeout(3000);
        pbxRtp = new DatagramSocket(0, LO);
        pbxRtp.setSoTimeout(3000);
        try (DatagramSocket free = new DatagramSocket(0, LO)) { sipPort = free.getLocalPort(); }
        sip = new SipClient(new SipClient.Config("127.0.0.1", pbx.getLocalPort(), "1001", "", "pw", sipPort, 300), bridge);
        bridge.attach(sip);
        sip.start();
        String register = waitFor("REGISTER");
        send(response(register, "200 OK", "", "Expires: 300\r\n"));
    }

    @AfterEach
    void tearDown() throws Exception {
        sip.stop();
        pbx.close();
        pbxRtp.close();
        callbacks.close();
        kiosk.close();
    }

    private String recv() throws Exception {
        DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
        pbx.receive(p);
        return new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
    }

    private String waitFor(String prefix) throws Exception {
        for (int i = 0; i < 20; i++) {
            String m = recv();
            if (m.startsWith(prefix)) return m;
        }
        fail("never saw " + prefix);
        return null;
    }

    private void send(String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        pbx.send(new DatagramPacket(data, data.length, new InetSocketAddress(LO, sipPort)));
    }

    private static String header(String message, String name) {
        for (String line : message.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return line.substring(name.length() + 1).trim();
        }
        return null;
    }

    private static String response(String request, String status, String toTag, String extra) {
        return "SIP/2.0 " + status + "\r\nVia: " + header(request, "Via") + "\r\nFrom: " + header(request, "From")
            + "\r\nTo: " + header(request, "To") + toTag + "\r\nCall-ID: " + header(request, "Call-ID")
            + "\r\nCSeq: " + header(request, "CSeq") + "\r\n" + extra + "Content-Length: 0\r\n\r\n";
    }

    private String invite(String callId, String extraHeaders) {
        String sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio "
            + pbxRtp.getLocalPort() + " RTP/AVP 0\r\n";
        return "INVITE sip:1001@127.0.0.1:" + sipPort + " SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP 127.0.0.1:" + pbx.getLocalPort() + ";branch=z9hG4bK" + callId + "\r\n"
            + "From: \"Front Desk\" <sip:900@127.0.0.1>;tag=f" + callId + "\r\nTo: <sip:1001@127.0.0.1>\r\n"
            + "Call-ID: " + callId + "\r\nCSeq: 1 INVITE\r\nContact: <sip:900@127.0.0.1:" + pbx.getLocalPort() + ">\r\n"
            + extraHeaders + "Content-Type: application/sdp\r\nContent-Length: " + sdp.length() + "\r\n\r\n" + sdp;
    }

    private String line(String message) { return message.substring(0, message.indexOf("\r\n")); }

    /** Next SIP response, skipping 100 Trying. */
    private String nextResponse() throws Exception {
        while (true) {
            String m = recv();
            if (m.startsWith("SIP/2.0") && !m.startsWith("SIP/2.0 100")) return m;
        }
    }

    @Test
    void callRingsAnswersAndCarriesAudioBothWays() throws Exception {
        kiosk.micFrames = 4;
        send(invite("k1", ""));
        String ringing = nextResponse();
        assertTrue(line(ringing).startsWith("SIP/2.0 180"), line(ringing));
        assertEquals("ringing", bridgeEvents.poll(3, TimeUnit.SECONDS));
        assertEquals("call call Front Desk (900)", kiosk.events.poll(3, TimeUnit.SECONDS));

        kiosk.answer();
        String ok = nextResponse();
        assertTrue(line(ok).startsWith("SIP/2.0 200"), line(ok));
        assertEquals("answered", bridgeEvents.poll(3, TimeUnit.SECONDS));
        send("ACK sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1;branch=z9hG4bKack\r\nFrom: " + header(ok, "From")
            + "\r\nTo: " + header(ok, "To") + "\r\nCall-ID: k1\r\nCSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n");

        // Kiosk mic → RTP to the PBX (PCMU silence is 0xff; mic audio is not).
        boolean heardMic = false;
        DatagramPacket rtp = new DatagramPacket(new byte[512], 512);
        for (int i = 0; i < 60 && !heardMic; i++) {
            pbxRtp.receive(rtp);
            heardMic = rtp.getData()[12] != (byte) 0xff;
        }
        assertTrue(heardMic, "kiosk microphone reaches the caller");

        // Caller RTP → kiosk speaker: 4 packets of 20 ms make one 80 ms frame.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("m=audio (\\d+)").matcher(ok);
        assertTrue(m.find());
        int port = Integer.parseInt(m.group(1));
        byte[] packet = new byte[12 + 160];
        packet[0] = (byte) 0x80;
        java.util.Arrays.fill(packet, 12, packet.length, (byte) 0x90);
        for (int i = 0; i < 8; i++) {
            packet[3] = (byte) i;
            pbxRtp.send(new DatagramPacket(packet, packet.length, new InetSocketAddress(LO, port)));
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (kiosk.frames.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(kiosk.frames.size() >= 1, "caller audio reaches the kiosk: " + kiosk.frames.size());

        send("BYE sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbx.getLocalPort() + ";branch=z9hG4bKbye\r\nFrom: "
            + header(ok, "From") + "\r\nTo: " + header(ok, "To") + "\r\nCall-ID: k1\r\nCSeq: 2 BYE\r\nContent-Length: 0\r\n\r\n");
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 200"));
        assertEquals("ended", bridgeEvents.poll(3, TimeUnit.SECONDS));
        String last = null;
        for (String e; (e = kiosk.events.poll(2, TimeUnit.SECONDS)) != null; ) last = e;
        assertEquals("action hangup", last);
    }

    @Test
    void declineOnKioskAnswers603() throws Exception {
        send(invite("k2", ""));
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 180"));
        kiosk.decline();
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 603"));
        assertEquals("ringing", bridgeEvents.poll(3, TimeUnit.SECONDS));
        assertEquals("declined", bridgeEvents.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void callerCancelStopsTheKioskRinging() throws Exception {
        String inv = invite("k3", "");
        send(inv);
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 180"));
        send("CANCEL sip:1001@127.0.0.1 SIP/2.0\r\nVia: " + header(inv, "Via") + "\r\nFrom: " + header(inv, "From")
            + "\r\nTo: " + header(inv, "To") + "\r\nCall-ID: k3\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n");
        String a = nextResponse(), b = nextResponse();
        assertTrue(a.contains("CANCEL") || b.contains("CANCEL"));
        assertTrue(line(a).startsWith("SIP/2.0 487") || line(b).startsWith("SIP/2.0 487"));
        String e;
        do { e = kiosk.events.poll(3, TimeUnit.SECONDS); } while (e != null && !e.equals("action cancel"));
        assertEquals("action cancel", e);
        assertEquals("ringing", bridgeEvents.poll(3, TimeUnit.SECONDS));
        assertEquals("cancelled", bridgeEvents.poll(3, TimeUnit.SECONDS));
    }

    @Test
    void kioskDndAnswers480WithoutRinging() throws Exception {
        kiosk.callStatus = "dnd";
        send(invite("k4", ""));
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 480"));
    }

    @Test
    void pageHeadersStillMeanBroadcast() throws Exception {
        kiosk.callStatus = "listening";
        send(invite("k5", "Alert-Info: <http://127.0.0.1>;info=Ring Answer\r\nCall-Info: <sip:900@127.0.0.1>;answer-after=0\r\n"));
        assertTrue(line(nextResponse()).startsWith("SIP/2.0 200"));
        assertEquals("call broadcast Front Desk (900)", kiosk.events.poll(3, TimeUnit.SECONDS));
    }
}
