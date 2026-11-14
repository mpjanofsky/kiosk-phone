// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kioskvoip.video.RtspSource;

/**
 * The caller's video socket of one call ({@code WS /api/intercom/video/<call>}), as Kiosk Satellite's own
 * {@code IntercomVideoLink} speaks it: we say {@code {"type":"camera","on":true}}, then send binary frames of a 9-byte
 * big-endian header (flags, bit 0 = key frame with SPS/PPS; u16 width; u16 height; u32 capture ms) and one H.264
 * access unit in Annex B form. The kiosk acks each frame ({@code {"type":"ack","n":k}}) and may ask for a key frame
 * ({@code {"type":"key"}}). With {@value #MAX_IN_FLIGHT} frames unacknowledged we drop until the next key frame, like
 * the kiosk does. A camera cannot be told to send a key frame, so a request just waits for the next one.
 */
public final class VideoLink implements RtspSource.Sink {
    static final int MAX_IN_FLIGHT = 6;
    static final int HEADER = 9;
    /** A cached key frame older than this is not shown at the start (it would be a misleading still). */
    static final long KEY_MAX_AGE_MS = 3000;
    private static final RtspSource.Frame END = new RtspSource.Frame(false, 0, 0, new byte[0], 0);

    private final IntercomClient client;
    private final int port;
    private final String call;
    private final BlockingQueue<RtspSource.Frame> queue = new ArrayBlockingQueue<>(8);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long startedMs = System.currentTimeMillis();
    private volatile long sent, acked, dropped, keyRequests;
    private volatile boolean waitKey = true;
    private volatile RtspSource.Frame cachedKey;
    private WebSocket ws;

    VideoLink(IntercomClient client, int port, String call) {
        this.client = client;
        this.port = port;
        this.call = call;
    }

    /** Opens the socket and starts the sender. {@code lastKey}: a recent key frame to show at once, or null. */
    void open(RtspSource.Frame lastKey) throws IOException {
        String path = "/api/intercom/video/" + call + "?token=" + client.token(call);
        ws = WebSocket.connect(client.host(), port, path, client.timeoutMs(), new WebSocket.Listener() {
            @Override public void onText(String text) { message(text); }
            @Override public void onClosed(int code, String reason) {
                if (!closed.get()) client.log("video " + IntercomClient.shortId(call) + " socket closed by kiosk (" + code + ")");
                close();
            }
        });
        ws.sendText("{\"type\":\"camera\",\"on\":true}");
        if (lastKey != null && lastKey.key && System.currentTimeMillis() - lastKey.receivedMs < KEY_MAX_AGE_MS) {
            cachedKey = lastKey;
            queue.offer(lastKey);
        }
        Thread sender = new Thread(this::sendLoop, "intercom-video");
        sender.setDaemon(true);
        sender.start();
        client.log("video " + IntercomClient.shortId(call) + " connected");
    }

    @Override public void onFrame(RtspSource.Frame frame) {
        if (closed.get()) return;
        if (!queue.offer(frame)) { // the sender is behind: start over at the next key frame
            queue.clear();
            waitKey = true;
            dropped++;
        }
    }

    public long framesSent() { return sent; }

    public long framesDropped() { return dropped; }

    public boolean isClosed() { return closed.get(); }

    /** Says camera off and closes; safe to call more than once and from any thread. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        queue.clear();
        queue.offer(END);
    }

    private void message(String text) {
        java.util.Map<String, Object> m;
        try {
            m = Json.object(text);
        } catch (IllegalArgumentException notJson) {
            return;
        }
        Object type = m.get("type");
        if ("ack".equals(type)) {
            Object n = m.get("n");
            if (n instanceof Long && (Long) n > acked && (Long) n <= sent) acked = (Long) n;
        } else if ("key".equals(type)) {
            keyRequests++;
            waitKey = true;
        }
        // {"type":"camera"} is the kiosk's own camera; we show nothing, so it needs no answer.
    }

    /** Header + access unit, the wire form of one frame. */
    static byte[] encode(RtspSource.Frame f, long captureMs) {
        byte[] out = new byte[HEADER + f.annexB.length];
        out[0] = (byte) (f.key ? 1 : 0);
        out[1] = (byte) (f.width >> 8);
        out[2] = (byte) f.width;
        out[3] = (byte) (f.height >> 8);
        out[4] = (byte) f.height;
        out[5] = (byte) (captureMs >> 24);
        out[6] = (byte) (captureMs >> 16);
        out[7] = (byte) (captureMs >> 8);
        out[8] = (byte) captureMs;
        System.arraycopy(f.annexB, 0, out, HEADER, f.annexB.length);
        return out;
    }

    private void sendLoop() {
        try {
            while (true) {
                RtspSource.Frame f = queue.poll(1, TimeUnit.SECONDS);
                if (f == END) break;
                if (f == null || closed.get()) continue;
                boolean behind = sent - acked >= MAX_IN_FLIGHT;
                if (behind || (waitKey && !f.key)) {
                    if (behind) waitKey = true;
                    dropped++;
                    continue;
                }
                // A cached key frame is a still from before the call: the live frames after it do not follow from it,
                // so the next thing shown must be a fresh key frame.
                waitKey = f == cachedKey;
                try {
                    ws.sendBinary(encode(f, Math.max(0, f.receivedMs - startedMs)));
                    sent++;
                } catch (IOException failure) {
                    close();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try { ws.sendText("{\"type\":\"camera\",\"on\":false}"); } catch (IOException ignored) { }
        ws.close(1000);
        client.log(String.format("video %s ended: %d frames sent, %d acked, %d dropped, %d key requests",
            IntercomClient.shortId(call), sent, acked, dropped, keyRequests));
    }
}
