// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives the kiosk's callbacks for calls we placed ({@code POST /api/intercom/call/<call>} with
 * {@code {"action":"answer"|"decline"|...}}, docs/protocol.md). Checks the Bearer token like KS does: signature with
 * our key, call id, expiry, single use. Anything else gets an error status and is not passed on.
 */
public final class CallbackServer implements Closeable {
    public interface Handler {
        /** {@code answer}, {@code decline}, {@code missed}, {@code hangup} or {@code cancel}. Runs on a server thread. */
        void onAction(String action);
    }

    private static final String PREFIX = "/api/intercom/call/";

    private final String key;
    private final ServerSocket server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> usedNonces = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 256; }
    };
    private final java.util.function.Consumer<String> log;

    /** Listens on {@code bindAddress}:{@code port} (0 = any free port). */
    public CallbackServer(String key, InetAddress bindAddress, int port, java.util.function.Consumer<String> log) throws IOException {
        this.key = key;
        this.log = log;
        server = new ServerSocket(port, 10, bindAddress);
        Thread t = new Thread(this::acceptLoop, "intercom-callback");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return server.getLocalPort(); }

    void register(String call, Handler handler) { handlers.put(call, handler); }

    void unregister(String call) { handlers.remove(call); }

    @Override public void close() throws IOException { server.close(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread h = new Thread(() -> handle(s), "intercom-callback-conn");
                h.setDaemon(true);
                h.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(3000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String[] request = Http.line(in).split(" ");
            Map<String, String> headers = Http.headers(in);
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length < 0 || length > 4096) { reply(out, 413, "{}"); return; }
            String body = new String(Http.exactly(in, length), StandardCharsets.UTF_8);
            if (request.length < 2 || !request[0].equals("POST") || !request[1].startsWith(PREFIX)) { reply(out, 404, "{}"); return; }
            String call = request[1].substring(PREFIX.length());
            String bearer = headers.getOrDefault("authorization", "");
            if (!bearer.startsWith("Bearer ") || !validToken(bearer.substring(7), call)) { reply(out, 403, "{}"); return; }
            Handler handler = handlers.get(call);
            if (handler == null) { reply(out, 404, "{}"); return; }
            Object action = Json.object(body).get("action");
            if (!(action instanceof String)) { reply(out, 400, "{}"); return; }
            reply(out, 200, "{\"ok\":true}");
            handler.onAction((String) action);
        } catch (IOException | RuntimeException failure) {
            log.accept("callback: " + failure.getMessage());
        }
    }

    private boolean validToken(String token, String call) {
        int dot = token.indexOf('.');
        if (dot < 0) return false;
        String payload = token.substring(0, dot);
        byte[] expected = IntercomAuth.hmac(key, payload);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException bad) {
            return false;
        }
        if (!MessageDigest.isEqual(expected, given)) return false;
        Map<String, Object> claims;
        try {
            claims = Json.object(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException bad) {
            return false;
        }
        if (!call.equals(claims.get("intercom")) || !(claims.get("exp") instanceof Long) || !(claims.get("n") instanceof String)) return false;
        if ((Long) claims.get("exp") < System.currentTimeMillis()) return false;
        synchronized (usedNonces) {
            return usedNonces.put((String) claims.get("n"), Boolean.TRUE) == null;
        }
    }

    private static void reply(OutputStream out, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + data.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
