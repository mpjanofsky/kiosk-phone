package kioskvoip.intercom;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A KS kiosk on loopback, per docs/protocol.md: identity on an admin port, calls and the audio WebSocket on a separate
 * endpoint port. Checks every token like the real one (signature, call id, sender, expiry, single use).
 */
final class FakeKiosk implements AutoCloseable {
    final String key;
    final ServerSocket admin, endpoint;
    volatile String callStatus = "listening";
    volatile boolean enabled = true, dnd = false, tls = false;
    volatile String reportedKeyDigest;
    /** Close the audio socket after this many binary frames (Dismiss); 0 = never. */
    volatile int dismissAfterFrames;
    /** Mic frames (80 ms of constant {@link #MIC_VALUE}) the kiosk sends on the audio socket as soon as it opens. */
    volatile int micFrames;
    static final short MIC_VALUE = 1234;
    static final String KIOSK_ID = "00112233aabbccdd";
    /** Set by the last {@code kind:"call"}: where the kiosk calls back, and the call id. */
    volatile String callId, callbackHost;
    volatile int callbackPort;
    final AtomicInteger identityRequests = new AtomicInteger();
    /** "call broadcast <name>", "action hangup", "text {...}", "audio closed", "reject <why>". */
    final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    final List<byte[]> frames = new CopyOnWriteArrayList<>();
    private final List<String> usedNonces = new CopyOnWriteArrayList<>();

    FakeKiosk(String key) throws IOException {
        this.key = key;
        reportedKeyDigest = IntercomAuth.digest(key);
        InetAddress lo = InetAddress.getLoopbackAddress();
        admin = new ServerSocket(0, 10, lo);
        endpoint = new ServerSocket(0, 10, lo);
        accept(admin);
        accept(endpoint);
    }

    int adminPort() { return admin.getLocalPort(); }

    @Override public void close() throws IOException {
        admin.close();
        endpoint.close();
    }

    private void accept(ServerSocket server) {
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread h = new Thread(() -> handle(s, server == admin), "fake-kiosk-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }, "fake-kiosk");
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket socket, boolean isAdmin) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String[] request = Http.line(in).split(" ");
            Map<String, String> headers = Http.headers(in);
            String method = request[0], target = request[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            String body = new String(Http.exactly(in, length), StandardCharsets.UTF_8);

            if (isAdmin) {
                if (method.equals("GET") && path.equals("/api/intercom/identity")) {
                    identityRequests.incrementAndGet();
                    json(out, 200, "{\"id\":\"00112233aabbccdd\",\"name\":\"Fake Kiosk\",\"version\":\"26.10.20\","
                        + "\"enabled\":" + enabled + ",\"key\":\"" + reportedKeyDigest + "\",\"dnd\":" + dnd
                        + ",\"endpoint\":{\"port\":" + endpoint.getLocalPort() + ",\"tls\":" + tls + "}}");
                } else {
                    json(out, 404, "{}");
                }
                return;
            }
            String bearer = headers.getOrDefault("authorization", "");
            if (method.equals("POST") && path.equals("/api/intercom/call")) {
                Map<String, Object> call = Json.object(body);
                if (!validToken(bearer.replaceFirst("^Bearer ", ""), (String) call.get("call"))) {
                    json(out, 403, "{\"status\":\"key\"}");
                    return;
                }
                Map<?, ?> from = (Map<?, ?>) call.get("from");
                events.add("call " + call.get("kind") + " " + from.get("name"));
                if ("call".equals(call.get("kind"))) {
                    callId = (String) call.get("call");
                    callbackHost = (String) from.get("address");
                    callbackPort = ((Long) from.get("port")).intValue();
                }
                json(out, 200, "{\"status\":\"" + callStatus + "\"}");
            } else if (method.equals("POST") && path.startsWith("/api/intercom/call/")) {
                String id = path.substring("/api/intercom/call/".length());
                if (!validToken(bearer.replaceFirst("^Bearer ", ""), id)) {
                    json(out, 403, "{}");
                    return;
                }
                events.add("action " + Json.object(body).get("action"));
                json(out, 200, "{\"ok\":true}");
            } else if (method.equals("GET") && path.startsWith("/api/intercom/audio/")) {
                String id = path.substring("/api/intercom/audio/".length());
                String token = target.substring(target.indexOf("?token=") + 7);
                if (!validToken(token, id)) {
                    json(out, 403, "{}");
                    return;
                }
                String accept = WebSocket.accept(headers.get("sec-websocket-key"));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (micFrames > 0) micTask(out);
                audio(in, out);
            } else {
                json(out, 404, "{}");
            }
        } catch (IOException | RuntimeException failure) {
            events.add("error " + failure);
        }
    }

    /** The person at the kiosk presses Answer / Decline: tell the caller, as KS does, with a token from the kiosk. */
    void answer() throws IOException { callback("answer"); }

    void decline() throws IOException { callback("decline"); }

    int callbackStatus(String action, String bearer) throws IOException {
        return Http.request(callbackHost, callbackPort, "POST", "/api/intercom/call/" + callId, bearer,
            "{\"action\":\"" + action + "\"}", 2000).status;
    }

    private void callback(String action) throws IOException {
        int status = callbackStatus(action, IntercomAuth.token(key, callId, KIOSK_ID));
        if (status != 200) throw new IOException("callback refused: HTTP " + status);
    }

    private void micTask(OutputStream out) {
        byte[] frame = new byte[2560];
        for (int i = 0; i < frame.length; i += 2) { frame[i] = (byte) MIC_VALUE; frame[i + 1] = (byte) (MIC_VALUE >> 8); }
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < micFrames; i++) {
                    synchronized (out) {
                        out.write(new byte[] { (byte) 0x82, (byte) 126, (byte) (frame.length >> 8), (byte) frame.length });
                        out.write(frame);
                        out.flush();
                    }
                    Thread.sleep(20);
                }
            } catch (IOException | InterruptedException ignored) { }
        }, "fake-kiosk-mic");
        t.setDaemon(true);
        t.start();
    }

    private void audio(InputStream in, OutputStream out) throws IOException {
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) break;
            if ((b1 & 0x80) == 0) { events.add("reject unmasked frame"); break; }
            int n = b1 & 0x7f;
            if (n == 126) n = (in.read() << 8) | in.read();
            byte[] mask = Http.exactly(in, 4), payload = Http.exactly(in, n);
            for (int i = 0; i < n; i++) payload[i] ^= mask[i & 3];
            int opcode = b0 & 0x0f;
            if (opcode == 0x1) {
                events.add("text " + new String(payload, StandardCharsets.UTF_8));
            } else if (opcode == 0x2) {
                frames.add(payload);
                if (dismissAfterFrames > 0 && frames.size() >= dismissAfterFrames) {
                    out.write(new byte[] { (byte) 0x88, 2, 0x03, (byte) 0xe8 }); // close 1000
                    out.flush();
                    dismissAfterFrames = 0;
                }
            } else if (opcode == 0x8) {
                out.write(new byte[] { (byte) 0x88, 0 });
                out.flush();
                break;
            }
        }
        events.add("audio closed");
    }

    private boolean validToken(String token, String call) {
        int dot = token.indexOf('.');
        if (dot < 0) return false;
        String payload = token.substring(0, dot);
        String sig = Base64.getUrlEncoder().encodeToString(IntercomAuth.hmac(key, payload));
        if (!sig.equals(token.substring(dot + 1))) { events.add("reject bad signature"); return false; }
        Map<String, Object> claims = Json.object(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        if (!call.equals(claims.get("intercom"))) { events.add("reject wrong call"); return false; }
        if (!"0123456789abcdef".equals(claims.get("from"))) { events.add("reject wrong sender"); return false; }
        long exp = (Long) claims.get("exp");
        if (exp < System.currentTimeMillis() || exp > System.currentTimeMillis() + 61_000) { events.add("reject exp"); return false; }
        String nonce = (String) claims.get("n");
        if (usedNonces.contains(nonce)) { events.add("reject reused token"); return false; }
        usedNonces.add(nonce);
        return true;
    }

    private static void json(OutputStream out, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + data.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
