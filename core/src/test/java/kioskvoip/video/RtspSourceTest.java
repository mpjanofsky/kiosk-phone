package kioskvoip.video;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RtspSource against a fake camera: Digest auth, DESCRIBE/SETUP/PLAY, interleaved RTP, STAP-A and FU-A. */
class RtspSourceTest {
    static final String SPS64 = "Z00AHpY1QWgk03AQEBAg", PPS64 = "aO48gA==";

    ServerSocket server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final BlockingQueue<RtspSource.Frame> frames = new LinkedBlockingQueue<>();
    volatile boolean digest = true;
    volatile boolean ok = true;

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::serve, "fake-camera");
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void tearDown() throws IOException { server.close(); }

    private void serve() {
        try (Socket s = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = s.getOutputStream();
            while (true) {
                String first = in.readLine();
                if (first == null) return;
                if (first.isEmpty()) continue;
                String cseq = "", auth = null;
                String h;
                while (!(h = in.readLine()).isEmpty()) {
                    if (h.startsWith("CSeq:")) cseq = h.substring(5).trim();
                    if (h.startsWith("Authorization:")) auth = h.substring(14).trim();
                }
                String[] f = first.split(" ");
                requests.add(f[0] + " " + f[1] + (auth == null ? "" : " auth"));
                if (digest && (auth == null || !auth.contains("response=\"" + expected(f[0], f[1]) + "\""))) {
                    reply(out, "401 Unauthorized", cseq, "WWW-Authenticate: Basic realm=\"cam\"\r\nWWW-Authenticate: Digest realm=\"cam\", nonce=\"abc123\"\r\n", "");
                    continue;
                }
                switch (f[0]) {
                    case "DESCRIBE":
                        reply(out, "200 OK", cseq, "Content-Type: application/sdp\r\nContent-Base: " + f[1] + "/\r\n",
                            "v=0\r\nm=audio 0 RTP/AVP 0\r\na=control:trackID=0\r\n"
                            + "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                            + "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=" + SPS64 + "," + PPS64 + "\r\n"
                            + "a=control:trackID=1\r\n");
                        break;
                    case "SETUP":
                        reply(out, "200 OK", cseq, "Session: 4711;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n", "");
                        break;
                    case "PLAY":
                        reply(out, "200 OK", cseq, "Session: 4711\r\n", "");
                        stream(out);
                        Thread.sleep(2000);
                        return;
                    default:
                        reply(out, "200 OK", cseq, "", "");
                }
            }
        } catch (IOException | InterruptedException ignored) { }
    }

    private static String expected(String method, String uri) {
        String ha1 = md5("peter:cam:se cret"), ha2 = md5(method + ":" + uri);
        return md5(ha1 + ":abc123:" + ha2);
    }

    private static void reply(OutputStream out, String status, String cseq, String headers, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        out.write(("RTSP/1.0 " + status + "\r\nCSeq: " + cseq + "\r\n" + headers + "Content-Length: " + b.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private int seq;

    private void rtp(OutputStream out, int channel, int payloadType, long ts, boolean marker, byte[] payload) throws IOException {
        byte[] p = new byte[12 + payload.length];
        p[0] = (byte) 0x80;
        p[1] = (byte) ((marker ? 0x80 : 0) | payloadType);
        p[2] = (byte) (seq >> 8); p[3] = (byte) seq;
        if (channel == 2) seq++; // one sequence per stream
        p[4] = (byte) (ts >> 24); p[5] = (byte) (ts >> 16); p[6] = (byte) (ts >> 8); p[7] = (byte) ts;
        System.arraycopy(payload, 0, p, 12, payload.length);
        out.write(new byte[] { '$', (byte) channel, (byte) (p.length >> 8), (byte) p.length });
        out.write(p);
    }

    /** IDR as FU-A in three pieces (no in-band SPS/PPS), then a P frame as a single NAL, then a STAP-A key frame. */
    private void stream(OutputStream out) throws IOException {
        byte[] idr = new byte[3000];
        idr[0] = 0x65;
        for (int i = 1; i < idr.length; i++) idr[i] = (byte) (i % 251 + 1);
        int[] cuts = { 1, 1000, 2000, idr.length };
        for (int i = 0; i < 3; i++) {
            ByteArrayOutputStream fu = new ByteArrayOutputStream();
            fu.write(0x7c); // FU indicator: NRI 3, type 28
            fu.write((i == 0 ? 0x80 : 0) | (i == 2 ? 0x40 : 0) | 5);
            fu.write(idr, cuts[i], cuts[i + 1] - cuts[i]);
            rtp(out, 2, 96, 9000, i == 2, fu.toByteArray());
        }
        rtp(out, 0, 0, 1, true, new byte[] { 1, 2, 3 }); // audio-ish on another channel: ignored
        rtp(out, 2, 96, 12600, true, new byte[] { 0x41, 9, 9, 9 });
        byte[] sps = RtspSource.unbase64(SPS64), pps = RtspSource.unbase64(PPS64), small = { 0x65, 7, 7 };
        ByteArrayOutputStream stap = new ByteArrayOutputStream();
        stap.write(0x18);
        for (byte[] n : new byte[][] { sps, pps, small }) { stap.write(n.length >> 8); stap.write(n.length); stap.write(n, 0, n.length); }
        rtp(out, 2, 96, 16200, true, stap.toByteArray());
        out.flush();
    }

    private static String md5(String s) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static int countStartCodes(byte[] d) {
        int n = 0;
        for (int i = 0; i + 3 < d.length; i++) if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) n++;
        return n;
    }

    @Test
    void digestAuthThenAssemblesFrames() throws Exception {
        String url = "rtsp://peter:se%20cret@127.0.0.1:" + server.getLocalPort() + "/av0_1";
        RtspSource source = new RtspSource(url, m -> { });
        source.start(frames::add);
        RtspSource.Frame key = frames.poll(3, TimeUnit.SECONDS);
        assertNotNull(key, "first frame; requests " + requests);
        assertTrue(key.key);
        assertEquals(720, key.width);
        assertEquals(576, key.height);
        assertEquals(3, countStartCodes(key.annexB), "SPS + PPS from the SDP in front of the IDR");
        assertEquals(0x67, key.annexB[4]);
        assertEquals(4 + RtspSource.unbase64(SPS64).length + 4 + RtspSource.unbase64(PPS64).length + 4 + 3000, key.annexB.length);
        assertEquals(0x65, key.annexB[key.annexB.length - 3000]);
        assertEquals((byte) (2999 % 251 + 1), key.annexB[key.annexB.length - 1]);

        RtspSource.Frame p = frames.poll(3, TimeUnit.SECONDS);
        assertNotNull(p);
        assertFalse(p.key);
        assertArrayEquals(new byte[] { 0, 0, 0, 1, 0x41, 9, 9, 9 }, p.annexB);

        RtspSource.Frame stap = frames.poll(3, TimeUnit.SECONDS);
        assertNotNull(stap);
        assertTrue(stap.key);
        assertEquals(3, countStartCodes(stap.annexB), "in-band SPS/PPS kept, nothing doubled");
        source.stop();

        assertEquals("DESCRIBE rtsp://127.0.0.1:" + server.getLocalPort() + "/av0_1", requests.get(0), "no credentials on the wire");
        assertEquals("DESCRIBE rtsp://127.0.0.1:" + server.getLocalPort() + "/av0_1 auth", requests.get(1));
        assertEquals("SETUP rtsp://127.0.0.1:" + server.getLocalPort() + "/av0_1/trackID=1 auth", requests.get(2));
        assertTrue(requests.get(3).startsWith("PLAY "));
    }

    @Test
    void rejectsWhatIsNotRtsp() {
        assertNotNull(RtspSource.problem("http://cam/stream"));
        assertNotNull(RtspSource.problem("rtsp:///nohost"));
        assertNull(RtspSource.problem("rtsp://user:pw@192.0.2.10:8554/door"));
    }
}
