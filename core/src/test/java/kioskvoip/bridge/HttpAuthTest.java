// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kioskvoip.video.RtspSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpAuthTest {
    private HttpServer server;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    /** A device like an Akuvox: 401 with {@code challenge}, 200 once {@code check} accepts the Authorization. */
    private int serve(String challenge, java.util.function.BiPredicate<String, String> check) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + auth);
            int code;
            if (auth != null && check.test(auth, exchange.getRequestMethod())) {
                code = 200;
            } else {
                exchange.getResponseHeaders().add("WWW-Authenticate", challenge);
                code = 401;
            }
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static String call(String spec) { return PageBridge.callUrl(CallButtons.parse("b1", spec)); }

    @Test void splitsUserInfoFromUrl() {
        HttpAuth a = HttpAuth.of("http://admin:p%40ss:w@10.0.0.5:8080/fcgi/OpenDoor?action=OpenDoor&DoorNum=1");
        assertEquals("http://10.0.0.5:8080/fcgi/OpenDoor?action=OpenDoor&DoorNum=1", a.url);
        assertEquals("/fcgi/OpenDoor?action=OpenDoor&DoorNum=1", a.uri);
        assertEquals("admin", a.user);
        assertEquals("p@ss:w", a.password);
        HttpAuth none = HttpAuth.of("http://h/x");
        assertFalse(none.hasCredentials());
        assertEquals("http://h/x", none.url);
    }

    @Test void digestWithQop() throws Exception {
        int port = serve("Digest realm=\"HTTPAPI\", qop=\"auth\", nonce=\"abc123\", opaque=\"xyz\"", (auth, method) -> {
            if (!auth.startsWith("Digest ")) return false;
            Map<String, String> p = RtspSource.params(auth.substring(7));
            String ha1 = HttpAuth.md5("admin:HTTPAPI:secret");
            String ha2 = HttpAuth.md5(method + ":" + p.get("uri"));
            String expected = HttpAuth.md5(ha1 + ":abc123:" + p.get("nc") + ":" + p.get("cnonce") + ":auth:" + ha2);
            return expected.equals(p.get("response")) && "xyz".equals(p.get("opaque"))
                && "/fcgi/OpenDoor?action=OpenDoor&DoorNum=1".equals(p.get("uri"));
        });
        assertNull(call("Gate | get:http://admin:secret@127.0.0.1:" + port + "/fcgi/OpenDoor?action=OpenDoor&DoorNum=1"));
        assertEquals(2, seen.size());
        assertTrue(seen.get(0).endsWith(" null"), "first try goes without credentials");
        assertTrue(seen.get(1).startsWith("GET "));
    }

    @Test void basicAndPost() throws Exception {
        String good = "Basic " + RtspSource.base64("admin:secret".getBytes(StandardCharsets.UTF_8));
        int port = serve("Basic realm=\"x\"", (auth, method) -> auth.equals(good) && method.equals("POST"));
        assertNull(call("Gate | url:http://admin:secret@127.0.0.1:" + port + "/hook"));
    }

    @Test void wrongPasswordAndNoCredentials() throws Exception {
        int port = serve("Digest realm=\"r\", nonce=\"n\"", (auth, method) -> false);
        assertEquals("HTTP 401", call("Gate | get:http://admin:wrong@127.0.0.1:" + port + "/x"));
        assertEquals(2, seen.size(), "retries once, not in a loop");
        seen.clear();
        assertEquals("HTTP 401", call("Gate | get:http://127.0.0.1:" + port + "/x"));
        assertEquals(1, seen.size());
    }

    @Test void failureNeverShowsThePassword() {
        String problem = call("Gate | get:http://admin:topsecret@127.0.0.1:1/x");
        assertNotNull(problem);
        assertFalse(problem.contains("topsecret"), problem);
    }
}
