// SPDX-License-Identifier: Apache-2.0
package kioskvoip.video;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Pulls H.264 from an RTSP camera (or go2rtc) over TCP and hands out whole access units in Annex B form. RTP over the
 * RTSP connection (interleaved), so no UDP ports. Basic and Digest auth from the URL's user info. One thread per
 * source; {@link #stop} ends it. Video only, no transcoding: what the camera sends is what the kiosk gets.
 */
public final class RtspSource {
    /** Where finished frames go, on the source thread. */
    public interface Sink {
        void onFrame(Frame frame);
    }

    /** One access unit. {@code key}: an IDR with SPS/PPS in front, so a decoder can start here. */
    public static final class Frame {
        public final boolean key;
        public final int width, height;
        public final byte[] annexB;
        /** Wall clock when the frame was complete, for the kiosk's capture-time field and staleness checks. */
        public final long receivedMs;

        public Frame(boolean key, int width, int height, byte[] annexB, long receivedMs) {
            this.key = key;
            this.width = width;
            this.height = height;
            this.annexB = annexB;
            this.receivedMs = receivedMs;
        }
    }

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    private final URI uri;
    private final String user, password;
    private final Consumer<String> log;
    private volatile Sink sink;
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread thread;

    private byte[] sps, pps;
    private int width, height;
    private int cseq;
    private String session, authHeader;
    private Map<String, String> digest;

    /** {@code url}: rtsp://[user:password@]host[:port]/path. Throws IllegalArgumentException for anything else. */
    public RtspSource(String url, Consumer<String> log) {
        try {
            URI parsed = new URI(url.trim());
            if (!"rtsp".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
                throw new IllegalArgumentException("video source must be rtsp://host/path");
            }
            String info = parsed.getRawUserInfo();
            String u = null, p = "";
            if (info != null) {
                int colon = info.indexOf(':');
                u = decode(colon < 0 ? info : info.substring(0, colon));
                p = colon < 0 ? "" : decode(info.substring(colon + 1));
            }
            user = u;
            password = p;
            // The URL the server sees never carries the credentials.
            uri = new URI("rtsp", null, parsed.getHost(), parsed.getPort(), parsed.getRawPath(), parsed.getRawQuery(), null);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("video source is not a valid URL");
        }
        this.log = log;
    }

    /** Checks a URL without connecting; null when usable, else what is wrong. */
    public static String problem(String url) {
        try {
            new RtspSource(url, m -> { });
            return null;
        } catch (IllegalArgumentException bad) {
            return bad.getMessage();
        }
    }

    public synchronized void start(Sink sink) {
        if (thread != null) return;
        this.sink = sink;
        thread = new Thread(this::run, "rtsp-video");
        thread.setDaemon(true);
        thread.start();
    }

    /** Swaps where frames go (null drops them); the connection stays up. */
    public void setSink(Sink sink) { this.sink = sink; }

    public void stop() {
        stopped = true;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    public boolean isStopped() { return stopped; }

    private void run() {
        try {
            stream();
        } catch (IOException | RuntimeException failure) {
            if (!stopped) log.accept("video source: " + failure.getMessage());
        } finally {
            stop();
        }
    }

    private void stream() throws IOException {
        int port = uri.getPort() > 0 ? uri.getPort() : 554;
        Socket s = new Socket();
        socket = s;
        if (stopped) return;
        s.connect(new InetSocketAddress(uri.getHost(), port), TIMEOUT_MS);
        s.setSoTimeout(TIMEOUT_MS);
        s.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
        OutputStream out = s.getOutputStream();
        String base = uri.toString();

        Response describe = request(in, out, "DESCRIBE", base, "Accept: application/sdp\r\n");
        String sdp = new String(describe.body, StandardCharsets.UTF_8);
        Track track = Track.parse(sdp, contentBase(describe, base));
        if (track == null) throw new IOException("no H.264 video in the camera's SDP");
        sps = track.sps;
        pps = track.pps;
        if (sps != null) setSize(sps);

        Response setup = request(in, out, "SETUP", track.control, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        String sess = setup.headers.get("session");
        if (sess == null) throw new IOException("SETUP returned no session");
        session = sess.split(";")[0].trim();
        int channel = 0;
        String transport = setup.headers.get("transport");
        if (transport != null) {
            int at = transport.indexOf("interleaved=");
            if (at >= 0) {
                String v = transport.substring(at + 12).split("[-;]")[0].trim();
                try { channel = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
            }
        }
        request(in, out, "PLAY", base, "Range: npt=0.000-\r\n");
        log.accept("video source playing (" + uri.getHost() + ", payload " + track.payload + ")");
        s.setSoTimeout(10_000);
        readRtp(in, out, channel, track.payload, base);
    }

    private void readRtp(InputStream in, OutputStream out, int channel, int payload, String base) throws IOException {
        H264.AccessUnit unit = new H264.AccessUnit();
        ByteArrayOutputStream fu = new ByteArrayOutputStream(64 * 1024);
        boolean fuOpen = false;
        long unitTs = -1;
        int lastSeq = -1;
        boolean broken = false;
        long keepAliveAt = System.currentTimeMillis() + 25_000;
        while (!stopped) {
            int b = in.read();
            if (b < 0) throw new IOException("camera closed the stream");
            if (b != '$') {
                // An RTSP reply in the middle of RTP (keep-alive answer): read and drop its header block.
                if (b == 'R') skipReply(in);
                continue;
            }
            int ch = in.read();
            int len = (in.read() << 8) | in.read();
            if (ch < 0 || len < 0) throw new IOException("camera closed the stream");
            byte[] pkt = exactly(in, len);
            if (System.currentTimeMillis() > keepAliveAt) {
                keepAliveAt = System.currentTimeMillis() + 25_000;
                String ka = "GET_PARAMETER " + base + " RTSP/1.0\r\nCSeq: " + (++cseq) + "\r\nSession: " + session + "\r\n"
                    + auth("GET_PARAMETER", base) + "\r\n";
                out.write(ka.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            if (ch != channel || len < 12) continue;
            int cc = pkt[0] & 0x0f;
            boolean extension = (pkt[0] & 0x10) != 0;
            boolean marker = (pkt[1] & 0x80) != 0;
            if ((pkt[1] & 0x7f) != payload) continue;
            int seq = ((pkt[2] & 0xff) << 8) | (pkt[3] & 0xff);
            long ts = ((long) (pkt[4] & 0xff) << 24) | ((pkt[5] & 0xff) << 16) | ((pkt[6] & 0xff) << 8) | (pkt[7] & 0xff);
            int off = 12 + cc * 4;
            if (extension && off + 4 <= len) off += 4 + ((((pkt[off + 2] & 0xff) << 8) | (pkt[off + 3] & 0xff)) * 4);
            if (off >= len) continue;
            if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xffff)) broken = true; // TCP should not lose any, but be safe
            lastSeq = seq;

            if (unitTs >= 0 && ts != unitTs && !unit.isEmpty()) { // new timestamp without a marker: flush the old one
                emit(unit, broken);
                broken = false;
                fuOpen = false;
                fu.reset();
            }
            unitTs = ts;

            int nalHeader = pkt[off] & 0xff, nalType = nalHeader & 0x1f;
            if (nalType >= 1 && nalType <= 23) {
                byte[] nal = new byte[len - off];
                System.arraycopy(pkt, off, nal, 0, nal.length);
                remember(nal);
                unit.add(nal);
            } else if (nalType == 24) { // STAP-A
                int p = off + 1;
                while (p + 2 <= len) {
                    int n = ((pkt[p] & 0xff) << 8) | (pkt[p + 1] & 0xff);
                    p += 2;
                    if (n == 0 || p + n > len) break;
                    byte[] nal = new byte[n];
                    System.arraycopy(pkt, p, nal, 0, n);
                    remember(nal);
                    unit.add(nal);
                    p += n;
                }
            } else if (nalType == 28 && off + 2 <= len) { // FU-A
                int fuHeader = pkt[off + 1] & 0xff;
                boolean start = (fuHeader & 0x80) != 0, end = (fuHeader & 0x40) != 0;
                if (start) {
                    fu.reset();
                    fu.write((nalHeader & 0xe0) | (fuHeader & 0x1f));
                    fuOpen = true;
                }
                if (fuOpen) {
                    fu.write(pkt, off + 2, len - off - 2);
                    if (fu.size() > MAX_FRAME_BYTES) { fuOpen = false; broken = true; }
                }
                if (end && fuOpen) {
                    unit.add(fu.toByteArray());
                    fuOpen = false;
                }
            }
            if (marker && !unit.isEmpty()) {
                emit(unit, broken);
                broken = false;
                unitTs = -1;
            }
        }
    }

    /** Hands out the unit unless it is damaged; a damaged one drops everything up to the next key frame downstream. */
    private void emit(H264.AccessUnit unit, boolean broken) {
        boolean key = unit.idr;
        byte[] data = unit.annexB(sps, pps);
        unit.reset();
        Sink target = sink;
        if (target == null || width == 0) return;
        if (broken && !key) return;
        target.onFrame(new Frame(key, width, height, data, System.currentTimeMillis()));
    }

    private void remember(byte[] nal) {
        int t = H264.type(nal);
        if (t == H264.NAL_SPS) {
            sps = nal;
            setSize(nal);
        } else if (t == H264.NAL_PPS) {
            pps = nal;
        }
    }

    private void setSize(byte[] spsNal) {
        int[] size = H264.size(spsNal);
        if (size != null) {
            width = size[0];
            height = size[1];
        }
    }

    // ── RTSP requests ──────────────────────────────────────────────────

    static final class Response {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private Response request(InputStream in, OutputStream out, String method, String url, String extra) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
            req.append("CSeq: ").append(++cseq).append("\r\n");
            req.append("User-Agent: kiosk-phone\r\n");
            if (session != null) req.append("Session: ").append(session).append("\r\n");
            req.append(auth(method, url));
            req.append(extra).append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Response r = read(in);
            if (r.status == 401 && attempt == 0 && user != null) {
                if (!challenge(r.headers.get("www-authenticate"))) break;
                continue;
            }
            if (r.status != 200) throw new IOException(method + " returned RTSP " + r.status);
            return r;
        }
        throw new IOException(method + ": camera refused the credentials");
    }

    /** Reads the reply to our last request, skipping any RTP that arrives first. */
    private static Response read(InputStream in) throws IOException {
        while (true) {
            in.mark(4);
            int b = in.read();
            if (b < 0) throw new IOException("camera closed the connection");
            if (b == '$') {
                in.read();
                int len = (in.read() << 8) | in.read();
                exactly(in, len);
                continue;
            }
            in.reset();
            String status = line(in);
            if (status.isEmpty()) continue;
            if (!status.startsWith("RTSP/")) throw new IOException("not an RTSP reply");
            String[] parts = status.split(" ", 3);
            int code;
            try { code = Integer.parseInt(parts[1]); } catch (RuntimeException bad) { throw new IOException("bad RTSP status line"); }
            Map<String, String> headers = new LinkedHashMap<>();
            String h;
            while (!(h = line(in)).isEmpty()) {
                int colon = h.indexOf(':');
                if (colon > 0) {
                    String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = h.substring(colon + 1).trim();
                    // Several WWW-Authenticate lines: prefer Digest.
                    if (headers.containsKey(name) && !(name.equals("www-authenticate") && value.startsWith("Digest"))) continue;
                    headers.put(name, value);
                }
            }
            int length = 0;
            try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
            if (length < 0 || length > 65536) throw new IOException("RTSP body too large");
            return new Response(code, headers, exactly(in, length));
        }
    }

    private static void skipReply(InputStream in) throws IOException {
        int length = 0;
        line(in); // rest of the status line
        String h;
        while (!(h = line(in)).isEmpty()) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try { length = Integer.parseInt(h.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        if (length > 0 && length < 65536) exactly(in, length);
    }

    private boolean challenge(String header) {
        if (header == null) return false;
        if (header.regionMatches(true, 0, "Digest", 0, 6)) {
            digest = params(header.substring(6));
            authHeader = null;
            return digest.containsKey("realm") && digest.containsKey("nonce");
        }
        if (header.regionMatches(true, 0, "Basic", 0, 5)) {
            digest = null;
            authHeader = "Basic " + base64((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            return true;
        }
        return false;
    }

    private String auth(String method, String url) {
        if (digest != null) {
            String realm = digest.get("realm"), nonce = digest.get("nonce");
            String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + url);
            return "Authorization: Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                + "\", uri=\"" + url + "\", response=\"" + md5(ha1 + ":" + nonce + ":" + ha2) + "\"\r\n";
        }
        return authHeader != null ? "Authorization: " + authHeader + "\r\n" : "";
    }

    private static String contentBase(Response describe, String fallback) {
        String b = describe.headers.get("content-base");
        if (b == null) b = describe.headers.get("content-location");
        return b != null ? b : fallback;
    }

    /** The H.264 media section of an SDP. */
    static final class Track {
        int payload = -1;
        String control;
        byte[] sps, pps;

        static Track parse(String sdp, String base) {
            Track t = null;
            boolean inVideo = false;
            for (String raw : sdp.split("\r?\n")) {
                String line = raw.trim();
                if (line.startsWith("m=")) {
                    if (t != null && t.payload >= 0) break;
                    inVideo = line.startsWith("m=video");
                    if (inVideo) {
                        t = new Track();
                        t.control = base; // payload is set by the H264 rtpmap below
                    }
                    continue;
                }
                if (!inVideo || t == null) continue;
                if (line.startsWith("a=rtpmap:")) {
                    String[] f = line.substring(9).split(" ", 2);
                    if (f.length == 2 && f[1].toUpperCase(Locale.ROOT).startsWith("H264/")) {
                        try { t.payload = Integer.parseInt(f[0].trim()); } catch (NumberFormatException ignored) { }
                    }
                } else if (line.startsWith("a=control:")) {
                    String c = line.substring(10).trim();
                    if (c.equals("*")) t.control = base;
                    else if (c.toLowerCase(Locale.ROOT).startsWith("rtsp://")) t.control = c;
                    else t.control = base.endsWith("/") ? base + c : base + "/" + c;
                } else if (line.startsWith("a=fmtp:")) {
                    int at = line.indexOf("sprop-parameter-sets=");
                    if (at >= 0) {
                        String[] sets = line.substring(at + 21).split(";")[0].trim().split(",");
                        for (String set : sets) {
                            byte[] nal = unbase64(set.trim());
                            if (nal == null || nal.length == 0) continue;
                            if (H264.type(nal) == H264.NAL_SPS) t.sps = nal;
                            else if (H264.type(nal) == H264.NAL_PPS) t.pps = nal;
                        }
                    }
                }
            }
            return t != null && t.payload >= 0 ? t : null;
        }
    }

    // ── small helpers (Android API 24 has no java.util.Base64) ─────────

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    public static String base64(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            s.append(B64.charAt(n >> 18 & 63)).append(B64.charAt(n >> 12 & 63));
            s.append(i + 1 < d.length ? B64.charAt(n >> 6 & 63) : '=').append(i + 2 < d.length ? B64.charAt(n & 63) : '=');
        }
        return s.toString();
    }

    static byte[] unbase64(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = B64.indexOf(c);
            if (v < 0) return null;
            value = (value << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((value >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static String md5(String value) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : h) s.append(String.format("%02x", b & 0xff));
            return s.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Map<String, String> params(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = header.length();
        while (i < n) {
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == ',')) i++;
            int eq = header.indexOf('=', i);
            if (eq < 0) break;
            String key = header.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            i = eq + 1;
            String value;
            if (i < n && header.charAt(i) == '"') {
                int close = header.indexOf('"', i + 1);
                if (close < 0) close = n;
                value = header.substring(i + 1, close);
                i = close + 1;
            } else {
                int comma = header.indexOf(',', i);
                if (comma < 0) comma = n;
                value = header.substring(i, comma).trim();
                i = comma;
            }
            out.put(key, value);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return s;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') s.append((char) b);
            if (s.length() > 8192) throw new IOException("RTSP line too long");
        }
        if (b < 0 && s.length() == 0) throw new IOException("camera closed the connection");
        return s.toString();
    }

    private static byte[] exactly(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(out, got, n - got);
            if (r < 0) throw new IOException("camera closed the connection");
            got += r;
        }
        return out;
    }
}
