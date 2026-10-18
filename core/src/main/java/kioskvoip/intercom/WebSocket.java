// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Minimal RFC 6455 client (plaintext, no extensions). Text and binary frames from the server go to the listener (a
 * broadcast ignores binary; a call hears the kiosk microphone there). Sends are synchronized; one reader thread per socket.
 */
final class WebSocket {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 64 * 1024;

    interface Listener {
        void onText(String text);
        /** A complete binary message (kiosk mic audio), on the reader thread. */
        default void onBinary(byte[] data) { }
        /** Called once, on the reader thread, when the socket ends for any reason. */
        void onClosed(int code, String reason);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private volatile boolean closeSent, closed;

    private WebSocket(Socket socket, InputStream in, Listener listener) throws IOException {
        this.socket = socket;
        this.in = in;
        this.out = socket.getOutputStream();
        this.listener = listener;
    }

    /**
     * Opens and starts the reader. {@code path} may carry a token in its query, so it never appears in exceptions.
     */
    static WebSocket connect(String host, int port, String path, int timeoutMs, Listener listener) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            String key = B64.encode(IntercomAuth.randomBytes(16));
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + Http.hostHeader(host, port) + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String status = Http.line(in);
            Map<String, String> headers = Http.headers(in);
            if (!status.startsWith("HTTP/1.1 101")) {
                String[] parts = status.split(" ", 3);
                throw new IOException("WebSocket upgrade refused: HTTP " + (parts.length > 1 ? parts[1] : "?"));
            }
            if (!accept(key).equals(headers.get("sec-websocket-accept"))) throw new IOException("WebSocket accept mismatch");
            socket.setSoTimeout(0); // the reader blocks until the kiosk sends or closes
            WebSocket ws = new WebSocket(socket, in, listener);
            Thread reader = new Thread(ws::readLoop, "intercom-ws");
            reader.setDaemon(true);
            reader.start();
            return ws;
        } catch (IOException | RuntimeException failure) {
            socket.close();
            throw failure;
        }
    }

    static String accept(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return B64.encode(hash);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    boolean isOpen() { return !closed && !closeSent; }

    void sendText(String text) throws IOException { send(0x1, text.getBytes(StandardCharsets.UTF_8)); }

    void sendBinary(byte[] data) throws IOException { send(0x2, data); }

    /** Sends a close frame (once) and lets the reader finish the handshake; the socket closes within a second. */
    void close(int code) {
        if (closed) return;
        try {
            if (!closeSent) {
                closeSent = true;
                send(0x8, new byte[] { (byte) (code >> 8), (byte) code }, true);
            }
            socket.setSoTimeout(1000);
        } catch (IOException ignored) {
            closeSocket();
        }
    }

    private void send(int opcode, byte[] payload) throws IOException { send(opcode, payload, false); }

    private synchronized void send(int opcode, byte[] payload, boolean closing) throws IOException {
        if (closed || (closeSent && !closing)) throw new IOException("WebSocket closed");
        int n = payload.length;
        byte[] header = new byte[14];
        int h = 0;
        header[h++] = (byte) (0x80 | opcode);
        if (n < 126) {
            header[h++] = (byte) (0x80 | n);
        } else if (n < 65536) {
            header[h++] = (byte) (0x80 | 126);
            header[h++] = (byte) (n >> 8);
            header[h++] = (byte) n;
        } else {
            header[h++] = (byte) (0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) header[h++] = (byte) ((long) n >> shift);
        }
        byte[] mask = IntercomAuth.randomBytes(4);
        System.arraycopy(mask, 0, header, h, 4);
        h += 4;
        byte[] frame = new byte[h + n];
        System.arraycopy(header, 0, frame, 0, h);
        for (int i = 0; i < n; i++) frame[h + i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(frame);
        out.flush();
    }

    private void readLoop() {
        int code = 1006;
        String reason = "connection lost";
        ByteArrayOutputStream message = null;
        try {
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                int opcode = b0 & 0x0f;
                long n = b1 & 0x7f;
                if (n == 126) n = (in.read() << 8) | in.read();
                else if (n == 127) {
                    n = 0;
                    for (int i = 0; i < 8; i++) n = (n << 8) | in.read();
                }
                if (n < 0 || n > MAX_FRAME) { reason = "frame too large"; code = 1009; break; }
                byte[] mask = (b1 & 0x80) != 0 ? Http.exactly(in, 4) : null;
                byte[] payload = Http.exactly(in, (int) n);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                boolean fin = (b0 & 0x80) != 0;
                if (opcode == 0x8) {
                    code = payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : 1005;
                    reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                    if (!closeSent) {
                        closeSent = true;
                        try { send(0x8, payload.length >= 2 ? new byte[] { payload[0], payload[1] } : new byte[0], true); }
                        catch (IOException ignored) { }
                    }
                    break;
                } else if (opcode == 0x9) {
                    try { send(0xA, payload); } catch (IOException ignored) { }
                } else if (opcode == 0x1 || (opcode == 0x0 && message != null)) {
                    if (message == null) message = new ByteArrayOutputStream();
                    message.write(payload);
                    if (message.size() > MAX_FRAME) { reason = "message too large"; code = 1009; break; }
                    if (fin) {
                        String text = new String(message.toByteArray(), StandardCharsets.UTF_8);
                        message = null;
                        try { listener.onText(text); } catch (RuntimeException ignored) { }
                    }
                } else if (opcode == 0x2 && fin) {
                    try { listener.onBinary(payload); } catch (RuntimeException ignored) { }
                } else if (opcode == 0x2 || opcode == 0x0) {
                    message = null; // fragmented binary: the kiosk sends whole 80 ms frames
                }
            }
        } catch (IOException failure) {
            if (closeSent && code == 1006) reason = "closed";
        } finally {
            closeSocket();
            try { listener.onClosed(code, reason); } catch (RuntimeException ignored) { }
        }
    }

    private void closeSocket() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
