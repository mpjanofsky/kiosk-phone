// SPDX-License-Identifier: Apache-2.0
package kioskvoip.sip;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.Map;

/**
 * Test tool that imitates a desk phone: dials a number (e.g. an intercom code like *805099) through the PBX with
 * digest auth, plays a 16 kHz mono PCM16 WAV as G.722 or G.711, then hangs up. Lives in kioskvoip.sip to reuse the
 * package-private SIP parser and digest code.
 *
 * Env: SIP_SERVER, PHONE_USER, PHONE_PASSWORD, DIAL, WAV; optional SIP_PORT (5060), PHONE_LOCAL_PORT (5072),
 * CANCEL_AFTER (seconds of ringing before the caller cancels; tests caller-cancel), HOLD (seconds of silence after the WAV, to keep the call open for someone to talk), REC (path of a 16 kHz mono WAV to write whatever the far end sends, e.g. a kiosk microphone; prints peak and RMS).
 */
public final class FakePhone {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatagramSocket sip, rtp;
    private final InetSocketAddress server;
    private final String user, password, localIp, dialUri, callId, fromTag;
    private final int localPort;
    private int cseq = 1;
    /** Via branch of the last INVITE; a non-2xx ACK belongs to that transaction and must reuse it. */
    private String inviteBranch;

    private FakePhone(String host, int port, String user, String password, String dial, int localPort) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        server = new InetSocketAddress(address, port);
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(address, port);
            localIp = probe.getLocalAddress().getHostAddress();
        }
        this.user = user;
        this.password = password;
        this.localPort = localPort;
        dialUri = "sip:" + dial + "@" + host;
        callId = hex(8) + "@kiosk-voip-fakephone";
        fromTag = hex(4);
        sip = new DatagramSocket(new InetSocketAddress(localPort));
        sip.setSoTimeout(60_000); // an ordinary call may ring until a person answers
        rtp = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(localIp), 0));
    }

    private static void event(String text) { System.out.println(LocalTime.now() + " " + text); }

    private static String hex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private String sdp() {
        return "v=0\r\no=- " + (RANDOM.nextInt() & 0x7fffffff) + " 1 IN IP4 " + localIp + "\r\ns=kiosk-voip-fakephone\r\n"
            + "c=IN IP4 " + localIp + "\r\nt=0 0\r\nm=audio " + rtp.getLocalPort() + " RTP/AVP 9 0 8\r\n"
            + "a=rtpmap:9 G722/8000\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=ptime:20\r\na=sendrecv\r\n";
    }

    private String request(String method, String uri, String toHeader, int seq, String auth, String body) {
        String branch = "z9hG4bK" + hex(6);
        if ("INVITE".equals(method)) inviteBranch = branch;
        return request(method, uri, toHeader, seq, auth, body, branch);
    }

    private String request(String method, String uri, String toHeader, int seq, String auth, String body, String branch) {
        StringBuilder sb = new StringBuilder(method).append(' ').append(uri).append(" SIP/2.0\r\n")
            .append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(localPort)
            .append(";branch=").append(branch).append(";rport\r\n")
            .append("Max-Forwards: 70\r\n")
            .append("From: <sip:").append(user).append('@').append(server.getHostString()).append(">;tag=").append(fromTag).append("\r\n")
            .append("To: ").append(toHeader).append("\r\n")
            .append("Call-ID: ").append(callId).append("\r\n")
            .append("CSeq: ").append(seq).append(' ').append(method).append("\r\n")
            .append("Contact: <sip:").append(user).append('@').append(localIp).append(':').append(localPort).append(">\r\n")
            .append("User-Agent: kiosk-voip-fakephone/0.1\r\n");
        if (auth != null) sb.append(auth).append("\r\n");
        if (!body.isEmpty()) sb.append("Content-Type: application/sdp\r\n");
        return sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n").append(body).toString();
    }

    private void send(String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sip.send(new DatagramPacket(data, data.length, server));
    }

    /** Next SIP response for our call, answering OPTIONS and stray requests on the way. */
    private SipMessage response() throws IOException {
        while (true) {
            DatagramPacket p = new DatagramPacket(new byte[8192], 8192);
            sip.receive(p);
            SipMessage m = SipMessage.parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            if (m == null) continue;
            if (m.isRequest()) {
                if ("BYE".equals(m.method())) throw new IOException("Remote hung up");
                continue;
            }
            if (callId.equals(m.header("call-id"))) return m;
        }
    }

    private String authHeader(SipMessage challenge, String method, String uri) {
        boolean proxy = challenge.status() == 407;
        Map<String, String> params = SipDigest.parseParams(challenge.header(proxy ? "proxy-authenticate" : "www-authenticate")); // leak-ok: header names
        String qop = params.get("qop") != null ? "auth" : null;
        String cnonce = hex(8), nc = "00000001";
        StringBuilder value = new StringBuilder(proxy ? "Proxy-Authorization" : "Authorization")
            .append(": Digest username=\"").append(user).append("\", realm=\"").append(params.get("realm"))
            .append("\", nonce=\"").append(params.get("nonce")).append("\", uri=\"").append(uri)
            .append("\", response=\"").append(SipDigest.response(user, params.get("realm"), password, method, uri,
                params.get("nonce"), qop, nc, cnonce)).append("\", algorithm=MD5");
        if (qop != null) value.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        if (params.get("opaque") != null) value.append(", opaque=\"").append(params.get("opaque")).append('"');
        return value.toString();
    }

    private static String uriOf(String header) {
        int lt = header.indexOf('<'), gt = header.indexOf('>');
        return lt >= 0 && gt > lt ? header.substring(lt + 1, gt) : header.split(";")[0].trim();
    }

    private void call(short[] pcm) throws Exception {
        String to = "<" + dialUri + ">";
        String body = sdp();
        send(request("INVITE", dialUri, to, cseq, null, body));
        SipMessage ok = null;
        double cancelAfter = Double.parseDouble(env("CANCEL_AFTER", "0"));
        boolean cancelled = false;
        while (ok == null) {
            SipMessage m;
            try {
                if (cancelAfter > 0 && !cancelled) sip.setSoTimeout((int) (cancelAfter * 1000));
                m = response();
            } catch (SocketTimeoutException ringing) {
                event("cancelling after " + cancelAfter + " s");
                send(request("CANCEL", dialUri, to, cseq, null, "", inviteBranch));
                cancelled = true;
                sip.setSoTimeout(10_000);
                continue;
            }
            int code = m.status();
            if (code < 200) continue;
            if (cancelled && code == 487) {
                send(request("ACK", dialUri, m.header("to"), cseq, null, "", inviteBranch));
                event("call cancelled: 487");
                return;
            }
            if (cancelled && m.header("cseq") != null && m.header("cseq").endsWith("CANCEL")) continue;
            if ((code == 401 || code == 407) && cseq == 1) {
                // ACK the challenge in its own transaction, then retry with credentials.
                send(request("ACK", dialUri, m.header("to"), cseq, null, "", inviteBranch));
                cseq++;
                send(request("INVITE", dialUri, to, cseq, authHeader(m, "INVITE", dialUri), body));
                continue;
            }
            if (code != 200) {
                send(request("ACK", dialUri, m.header("to"), cseq, null, "", inviteBranch));
                throw new IOException("Call failed: " + code + " " + m.reason());
            }
            ok = m;
        }
        String remoteTo = ok.header("to");
        String target = ok.header("contact") != null ? uriOf(ok.header("contact")) : dialUri;
        send(request("ACK", target, remoteTo, cseq, null, ""));
        Sdp.Offer answer = Sdp.parseOffer(ok.body, new int[] {Sdp.G722, Sdp.PCMU, Sdp.PCMA});
        event("answered, codec " + Sdp.codecName(answer.payload) + ", sending " + pcm.length / 16000.0 + " s");

        Recorder recorder = System.getenv("REC") != null && !System.getenv("REC").isEmpty() ? new Recorder(answer.payload) : null;
        if (recorder != null) recorder.start();
        stream(pcm, answer);
        if (recorder != null) recorder.finish(System.getenv("REC"));

        send(request("BYE", target, remoteTo, ++cseq, null, ""));
        sip.setSoTimeout(3000);
        try {
            SipMessage byeOk = response();
            event("hung up: " + byeOk.status() + " " + byeOk.reason());
        } catch (SocketTimeoutException timeout) {
            event("hung up, no reply to BYE");
        }
    }

    /** Decodes incoming RTP into 16 kHz PCM so a test can check what the far end (kiosk mic) actually sent. */
    private final class Recorder {
        private final int payload;
        private final G722.Decoder g722 = new G722.Decoder();
        private final java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        private volatile boolean running = true;
        private volatile int packets;
        private Thread thread;
        private short previous;

        Recorder(int payload) { this.payload = payload; }

        void start() {
            thread = new Thread(() -> {
                byte[] buf = new byte[2048];
                try { rtp.setSoTimeout(200); } catch (java.net.SocketException ignored) { }
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { rtp.receive(p); } catch (SocketTimeoutException t) { continue; } catch (IOException closed) { return; }
                    int n = p.getLength() - 12;
                    if (n <= 0 || (buf[0] & 0xc0) != 0x80 || (buf[1] & 0x7f) != payload) continue;
                    packets++;
                    short[] out;
                    if (payload == Sdp.G722) {
                        out = g722.decode(buf, 12, n);
                    } else {
                        out = new short[n * 2];
                        for (int i = 0; i < n; i++) {
                            short x = payload == Sdp.PCMU ? G711.muToLinear(buf[12 + i]) : G711.aToLinear(buf[12 + i]);
                            out[2 * i] = (short) ((previous + x) / 2);
                            out[2 * i + 1] = x;
                            previous = x;
                        }
                    }
                    synchronized (pcm) { for (short v : out) { pcm.write(v & 0xff); pcm.write((v >> 8) & 0xff); } }
                }
            }, "fake-phone-rec");
            thread.setDaemon(true);
            thread.start();
        }

        void finish(String path) throws Exception {
            Thread.sleep(300);
            running = false;
            thread.join(1000);
            byte[] data;
            synchronized (pcm) { data = pcm.toByteArray(); }
            ByteBuffer samples = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int peak = 0, loud = 0, count = data.length / 2;
            double sum = 0;
            for (int i = 0; i < count; i++) {
                int v = samples.getShort();
                peak = Math.max(peak, Math.abs(v));
                sum += (double) v * v;
                if (Math.abs(v) > 300) loud++;
            }
            ByteBuffer wav = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.length).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
            Files.write(Paths.get(path), wav.array());
            event(String.format("received %d RTP packets (%.1f s): peak %d/32767, rms %.0f, %.0f%% of samples above 300; wrote %s",
                packets, count / 16000.0, peak, count == 0 ? 0 : Math.sqrt(sum / count), count == 0 ? 0 : 100.0 * loud / count, path));
        }
    }

    private void stream(short[] pcm, Sdp.Offer remote) throws Exception {
        InetSocketAddress to = new InetSocketAddress(remote.address, remote.port);
        G722.Encoder g722 = new G722.Encoder();
        int seq = RANDOM.nextInt(65536), ts = RANDOM.nextInt(), ssrc = RANDOM.nextInt();
        long next = System.nanoTime();
        int packets = 0;
        // Add 0.5 s of silence so the far end hears the tail.
        int holdSamples = (int) (Double.parseDouble(env("HOLD", "0")) * 16000);
        short[] padded = java.util.Arrays.copyOf(pcm, pcm.length + 8000 + holdSamples + (320 - pcm.length % 320) % 320);
        // 50 ms fade-in: a WAV that starts abruptly pops on the far end.
        for (int i = 0; i < 800 && i < padded.length; i++) padded[i] = (short) (padded[i] * i / 800);
        for (int off = 0; off < padded.length; off += 320) {
            short[] frame = java.util.Arrays.copyOfRange(padded, off, off + 320);
            byte[] payload;
            if (remote.payload == Sdp.G722) {
                payload = g722.encode(frame);
            } else {
                payload = new byte[160];
                for (int i = 0; i < 160; i++) {
                    short s = (short) ((frame[2 * i] + frame[2 * i + 1]) / 2);
                    payload[i] = remote.payload == Sdp.PCMU ? G711.linearToMu(s) : G711.linearToA(s);
                }
            }
            byte[] packet = new byte[12 + payload.length];
            packet[0] = (byte) 0x80;
            packet[1] = (byte) (remote.payload | (packets == 0 ? 0x80 : 0));
            packet[2] = (byte) (seq >>> 8); packet[3] = (byte) seq;
            packet[4] = (byte) (ts >>> 24); packet[5] = (byte) (ts >>> 16); packet[6] = (byte) (ts >>> 8); packet[7] = (byte) ts;
            packet[8] = (byte) (ssrc >>> 24); packet[9] = (byte) (ssrc >>> 16); packet[10] = (byte) (ssrc >>> 8); packet[11] = (byte) ssrc;
            System.arraycopy(payload, 0, packet, 12, payload.length);
            rtp.send(new DatagramPacket(packet, packet.length, to));
            packets++;
            seq = (seq + 1) & 0xffff;
            ts += 160;
            next += 20_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        }
        event("sent " + packets + " RTP packets");
    }

    /** Reads a 16 kHz mono PCM16 WAV (data chunk only). */
    public static short[] readWav(String path) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(path))).order(ByteOrder.LITTLE_ENDIAN);
        b.position(12);
        int channels = 0, rate = 0, bits = 0;
        while (b.remaining() >= 8) {
            byte[] id = new byte[4];
            b.get(id);
            int size = b.getInt();
            String chunk = new String(id, StandardCharsets.US_ASCII);
            if (chunk.equals("fmt ")) {
                int start = b.position();
                b.getShort();
                channels = b.getShort();
                rate = b.getInt();
                b.getInt();
                b.getShort();
                bits = b.getShort();
                b.position(start + size);
            } else if (chunk.equals("data")) {
                if (channels != 1 || rate != 16000 || bits != 16) throw new IOException("WAV must be 16 kHz mono 16-bit");
                short[] out = new short[size / 2];
                b.asShortBuffer().get(out);
                return out;
            } else {
                b.position(b.position() + size + (size & 1));
            }
        }
        throw new IOException("WAV has no data chunk");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isEmpty()) return value;
        if (fallback == null) throw new IllegalArgumentException("Set " + name);
        return fallback;
    }

    public static void main(String[] args) throws Exception {
        short[] pcm = readWav(env("WAV", null));
        FakePhone phone = new FakePhone(env("SIP_SERVER", null), Integer.parseInt(env("SIP_PORT", "5060")),
            env("PHONE_USER", null), env("PHONE_PASSWORD", null), env("DIAL", null),
            Integer.parseInt(env("PHONE_LOCAL_PORT", "5072")));
        event("dialing " + env("DIAL", null));
        try {
            phone.call(pcm);
        } finally {
            phone.sip.close();
            phone.rtp.close();
        }
    }
}
