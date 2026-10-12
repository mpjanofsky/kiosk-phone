// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal plaintext HTTP/1.1 client: one request per connection, small bodies. java.net.http is not on Android and
 * HttpURLConnection behaves differently there; this is small enough to own.
 */
final class Http {
    static final int MAX_BODY = 64 * 1024;

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Http() { }

    /** {@code body} null means no body. {@code bearer} goes into Authorization and is never logged. */
    static Response request(String host, int port, String method, String path, String bearer, String body, int timeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader(host, port)).append("\r\n")
                .append("Connection: close\r\nAccept: application/json\r\n");
            if (bearer != null) head.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if (body != null) head.append("Content-Type: application/json\r\n");
            if (body != null || "POST".equals(method)) head.append("Content-Length: ").append(payload.length).append("\r\n");
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            return read(socket.getInputStream());
        }
    }

    static String hostHeader(String host, int port) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    private static Response read(InputStream in) throws IOException {
        String statusLine = line(in);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new IOException("Not an HTTP response");
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException bad) {
            throw new IOException("Bad HTTP status line");
        }
        Map<String, String> headers = headers(in);
        byte[] body;
        String length = headers.get("content-length");
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))) {
            body = chunked(in);
        } else if (length != null) {
            int n;
            try {
                n = Integer.parseInt(length.trim());
            } catch (NumberFormatException bad) {
                throw new IOException("Bad Content-Length");
            }
            if (n < 0 || n > MAX_BODY) throw new IOException("Response body too large");
            body = exactly(in, n);
        } else {
            body = toEnd(in);
        }
        return new Response(status, new String(body, StandardCharsets.UTF_8));
    }

    /** Reads header lines up to the blank line; names lower-cased. */
    static Map<String, String> headers(InputStream in) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        for (int count = 0; ; count++) {
            String header = line(in);
            if (header.isEmpty()) return headers;
            if (count > 100) throw new IOException("Too many headers");
            int colon = header.indexOf(':');
            if (colon > 0) headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
        }
    }

    /** One CRLF- or LF-terminated line, read byte by byte so nothing past it is consumed. */
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed mid-header");
            if (b == '\n') break;
            if (out.size() > 8192) throw new IOException("Header line too long");
            out.write(b);
        }
        byte[] bytes = out.toByteArray();
        int n = bytes.length;
        if (n > 0 && bytes[n - 1] == '\r') n--;
        return new String(bytes, 0, n, StandardCharsets.ISO_8859_1);
    }

    static byte[] exactly(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(data, got, n - got);
            if (r < 0) throw new IOException("Connection closed mid-body");
            got += r;
        }
        return data;
    }

    private static byte[] toEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int r;
        while ((r = in.read(buffer)) >= 0) {
            out.write(buffer, 0, r);
            if (out.size() > MAX_BODY) throw new IOException("Response body too large");
        }
        return out.toByteArray();
    }

    private static byte[] chunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String size = line(in);
            int semicolon = size.indexOf(';');
            int n;
            try {
                n = Integer.parseInt((semicolon >= 0 ? size.substring(0, semicolon) : size).trim(), 16);
            } catch (NumberFormatException bad) {
                throw new IOException("Bad chunk size");
            }
            if (n == 0) {
                while (!line(in).isEmpty()) { } // trailers
                return out.toByteArray();
            }
            if (n < 0 || out.size() + n > MAX_BODY) throw new IOException("Response body too large");
            out.write(exactly(in, n));
            line(in);
        }
    }
}
