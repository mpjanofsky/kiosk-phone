package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kioskvoip.sip.SipClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Kiosk leg (fake) → OutboundBridge → SipClient → a scripted PBX on loopback. */
class OutboundBridgeTest {
    static final InetAddress LO = InetAddress.getLoopbackAddress();

    /** Answers REGISTER at once and scripts the reply to the first INVITE by {@code mode}. */
    static final class Pbx implements Runnable {
        final DatagramSocket socket, rtp;
        volatile String mode = "answer"; // answer | busy | ring (180 only)
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile InetSocketAddress client;
        volatile String inviteCallId = "";
        volatile boolean running = true;

        Pbx() throws Exception {
            socket = new DatagramSocket(0, LO);
            socket.setSoTimeout(100);
            rtp = new DatagramSocket(0, LO);
            Thread t = new Thread(this, "fake-pbx");
            t.setDaemon(true);
            t.start();
        }

        public void run() {
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(new byte[4096], 4096);
                    socket.receive(p);
                    client = new InetSocketAddress(p.getAddress(), p.getPort());
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    String method = text.substring(0, text.indexOf(' '));
                    if (text.startsWith("SIP/2.0")) continue;
                    requests.add(method);
                    switch (method) {
                        case "REGISTER": case "BYE": reply(text, "200 OK", ""); break;
                        case "CANCEL": reply(text, "200 OK", ""); replyTo(text, "487 Request Terminated", "INVITE"); break;
                        case "INVITE":
                            inviteCallId = header(text, "call-id");
                            if (mode.equals("busy")) reply(text, "486 Busy Here", "");
                            else if (mode.equals("ring")) reply(text, "180 Ringing", "");
                            else {
                                String sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\nm=audio "
                                    + rtp.getLocalPort() + " RTP/AVP 0\r\n";
                                reply(text, "200 OK", "Contact: <sip:5001@127.0.0.1:" + socket.getLocalPort()
                                    + ">\r\nContent-Type: application/sdp\r\n", sdp);
                            }
                            break;
                        default: break;
                    }
                } catch (java.net.SocketTimeoutException idle) {
                    // poll again
                } catch (Exception closed) {
                    return;
                }
            }
        }

        private static String header(String text, String name) {
            for (String line : text.split("\r\n")) if (line.toLowerCase().startsWith(name + ":")) return line.substring(name.length() + 1).trim();
            return "";
        }

        void reply(String req, String status, String extra) throws Exception { reply(req, status, extra, ""); }

        void replyTo(String req, String status, String method) throws Exception {
            String cseq = header(req, "cseq").split(" ")[0] + " " + method;
            send("SIP/2.0 " + status + "\r\nVia: " + header(req, "via") + "\r\nFrom: " + header(req, "from") + "\r\nTo: " + header(req, "to")
                + ";tag=pbx\r\nCall-ID: " + header(req, "call-id") + "\r\nCSeq: " + cseq + "\r\nContent-Length: 0\r\n\r\n");
        }

        void reply(String req, String status, String extra, String body) throws Exception {
            send("SIP/2.0 " + status + "\r\nVia: " + header(req, "via") + "\r\nFrom: " + header(req, "from") + "\r\nTo: " + header(req, "to")
                + ";tag=pbx\r\nCall-ID: " + header(req, "call-id") + "\r\nCSeq: " + header(req, "cseq") + "\r\n" + extra
                + "Content-Length: " + body.length() + "\r\n\r\n" + body);
        }

        void send(String text) throws Exception {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, client));
        }

        boolean waitFor(String method) throws Exception {
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                if (requests.contains(method)) return true;
                Thread.sleep(10);
            }
            return false;
        }
    }

    Pbx pbx;
    SipClient sip;
    OutboundBridge bridge;
    final BlockingQueue<String> events = new LinkedBlockingQueue<>(), leg = new LinkedBlockingQueue<>();
    final PhoneBook.Entry entry = PhoneBook.parse("Reception = 5001").entries().get(0);

    final OutboundBridge.Leg kiosk = new OutboundBridge.Leg() {
        public void answered() { leg.add("answered"); }
        public void end(String outcome) { leg.add("end " + outcome); }
        public void write(short[] pcm) { leg.add("audio"); }
    };

    @BeforeEach
    void setUp() throws Exception {
        pbx = new Pbx();
        int local;
        try (DatagramSocket free = new DatagramSocket(0, LO)) { local = free.getLocalPort(); }
        sip = new SipClient(new SipClient.Config("127.0.0.1", pbx.socket.getLocalPort(), "1001", "", "pw", local, 300),
            new SipClient.Listener() {
                public void onRegistration(boolean ok, String detail) { }
                public SipClient.Answer onInvite(String caller, String codec) { return SipClient.Answer.busy(); }
                public void onCallEnded(String reason, long packets, long dropped) { }
                public void log(String message) { }
            });
        bridge = new OutboundBridge(sip, new OutboundBridge.Events() {
            public void onOutgoing(String who, String state, String detail) { events.add(state); }
            public void log(String message) { }
        });
        sip.start();
        assertTrue(pbx.waitFor("REGISTER"));
        Thread.sleep(150); // let the 200 land
    }

    @AfterEach
    void tearDown() {
        pbx.running = false;
        sip.stop();
        pbx.socket.close();
        pbx.rtp.close();
    }

    private String next(BlockingQueue<String> queue) throws Exception { return queue.poll(3, TimeUnit.SECONDS); }

    @Test
    void answeredCallCompletesTheKioskLegAndKioskHangupEndsTheSipCall() throws Exception {
        assertNull(bridge.place(entry, kiosk));
        assertEquals("answered", next(events));
        assertEquals("answered", next(leg));
        bridge.kioskMic(new short[320]);
        bridge.kioskEnded("kiosk hung up");
        assertTrue(pbx.waitFor("BYE"));
        assertEquals("ended", next(events));
        assertNull(leg.poll(300, TimeUnit.MILLISECONDS), "the kiosk ended it, so it is not told again");
        assertNull(bridge.place(entry, kiosk), "free again");
    }

    @Test
    void busyFarEndDeclinesTheKioskLeg() throws Exception {
        pbx.mode = "busy";
        assertNull(bridge.place(entry, kiosk));
        assertEquals("busy", next(events));
        assertEquals("end decline", next(leg));
    }

    @Test
    void kioskCancelWhileRingingSendsCancelAndIsNotReportedBack() throws Exception {
        pbx.mode = "ring";
        assertNull(bridge.place(entry, kiosk));
        assertEquals("ringing", next(events));
        bridge.kioskEnded("cancel");
        assertTrue(pbx.waitFor("CANCEL"));
        assertEquals("cancelled", next(events));
        assertNull(leg.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void secondCallIsRefusedWhileOneIsUp() throws Exception {
        pbx.mode = "ring";
        assertNull(bridge.place(entry, kiosk));
        assertEquals("Busy", bridge.place(entry, kiosk));
    }

    @Test
    void remoteHangupEndsTheKioskLeg() throws Exception {
        assertNull(bridge.place(entry, kiosk));
        assertEquals("answered", next(leg));
        // The PBX hangs up: BYE to the client from the dialog the client opened.
        pbx.send("BYE sip:1001@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:" + pbx.socket.getLocalPort() + ";branch=z9hG4bKx\r\n"
            + "From: <sip:5001@127.0.0.1>;tag=pbx\r\nTo: <sip:1001@127.0.0.1>;tag=x\r\nCall-ID: " + pbx.inviteCallId
            + "\r\nCSeq: 1 BYE\r\nContent-Length: 0\r\n\r\n");
        assertEquals("end hangup", next(leg));
    }
}
