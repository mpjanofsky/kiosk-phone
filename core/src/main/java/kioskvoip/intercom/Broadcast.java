// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running one-way broadcast. {@link #write} takes 16 kHz PCM in any chunk size (e.g. 20 ms RTP packets), cuts it
 * into 80 ms frames and hands them to a sender thread, so a slow kiosk never blocks the caller. The audio arrives in
 * real time from the PBX, so frames are sent as they fill, without extra pacing.
 */
public final class Broadcast {
    /** Frames waiting to be sent; beyond this (2 s) the oldest is dropped. */
    static final int QUEUE_FRAMES = 25;
    private static final byte[] END = new byte[0];

    private final IntercomClient client;
    private final int port;
    private final String call;
    private final Runnable onKioskEnded;
    private final java.util.function.Consumer<short[]> onMic;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_FRAMES + 1);
    private final AtomicBoolean ended = new AtomicBoolean();
    private final byte[] frame = new byte[IntercomClient.FRAME_BYTES];
    private int filled;
    private volatile boolean kioskClosed;
    private volatile long sent, dropped;
    private WebSocket ws;

    Broadcast(IntercomClient client, int port, String call, Runnable onKioskEnded) {
        this(client, port, call, onKioskEnded, null);
    }

    /** {@code onMic} (calls only): receives each frame of kiosk microphone audio as 16 kHz PCM, on the reader thread. */
    Broadcast(IntercomClient client, int port, String call, Runnable onKioskEnded, java.util.function.Consumer<short[]> onMic) {
        this.onMic = onMic;
        this.client = client;
        this.port = port;
        this.call = call;
        this.onKioskEnded = onKioskEnded;
    }

    void open() throws IOException {
        String path = "/api/intercom/audio/" + call + "?token=" + client.token(call);
        ws = WebSocket.connect(client.host(), port, path, client.timeoutMs(), new WebSocket.Listener() {
            @Override public void onText(String text) {
                client.log("broadcast " + IntercomClient.shortId(call) + " kiosk says " + abbreviate(text));
                if (isEnd(text)) kioskEnded();
            }
            @Override public void onBinary(byte[] data) {
                if (onMic == null || data.length < 2 || ended.get()) return;
                short[] pcm = new short[data.length / 2];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((data[2 * i] & 0xff) | (data[2 * i + 1] << 8));
                onMic.accept(pcm);
            }
            @Override public void onClosed(int code, String reason) {
                if (!ended.get()) {
                    client.log("broadcast " + IntercomClient.shortId(call) + " audio socket closed by kiosk (" + code + ")");
                    kioskEnded();
                }
            }
        });
        ws.sendText("{\"type\":\"talk\",\"on\":true}");
        Thread sender = new Thread(this::sendLoop, "intercom-send");
        sender.setDaemon(true);
        sender.start();
    }

    public String callId() { return call; }

    public long framesSent() { return sent; }

    public long framesDropped() { return dropped; }

    /** True once the kiosk has ended the broadcast (Dismiss, socket loss). */
    public boolean endedByKiosk() { return kioskClosed; }

    /** Queues audio; safe to call from the RTP thread. Ignored after {@link #end}. */
    public void write(short[] pcm16k) {
        if (ended.get()) return;
        synchronized (frame) {
            for (short sample : pcm16k) {
                frame[filled++] = (byte) sample;
                frame[filled++] = (byte) (sample >> 8);
                if (filled == frame.length) {
                    enqueue(frame.clone());
                    filled = 0;
                }
            }
        }
    }

    /** Pads and sends the last partial frame, then ends the call on the sender thread. Returns at once. */
    public void end() {
        if (!ended.compareAndSet(false, true)) return;
        synchronized (frame) {
            if (filled > 0) {
                java.util.Arrays.fill(frame, filled, frame.length, (byte) 0);
                enqueue(frame.clone());
                filled = 0;
            }
        }
        while (!queue.offer(END)) queue.poll();
    }

    /** Called with the frame lock held, so there is one producer; the spare slot is for END. */
    private void enqueue(byte[] data) {
        if (queue.size() >= QUEUE_FRAMES && queue.poll() != null) dropped++;
        queue.offer(data);
    }

    private static boolean isEnd(String text) {
        try {
            return "end".equals(Json.object(text).get("type"));
        } catch (IllegalArgumentException notJson) {
            return false;
        }
    }

    private void kioskEnded() {
        kioskClosed = true;
        if (ended.compareAndSet(false, true)) {
            queue.clear();
            queue.offer(END);
            if (onKioskEnded != null) {
                try { onKioskEnded.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void sendLoop() {
        try {
            while (true) {
                byte[] data = queue.poll(1, TimeUnit.SECONDS);
                if (data == END) break;
                if (data == null || kioskClosed) continue;
                try {
                    ws.sendBinary(data);
                    sent++;
                } catch (IOException failure) {
                    kioskEnded();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!kioskClosed) {
            try { ws.sendText("{\"type\":\"end\"}"); } catch (IOException ignored) { }
        }
        ws.close(1000);
        client.hangup(port, call, "hangup");
        client.log(String.format("broadcast %s ended: %d frames (%.1f s) sent, %d dropped%s",
            IntercomClient.shortId(call), sent, sent * 0.08, dropped, kioskClosed ? ", ended by kiosk" : ""));
    }

    private static String abbreviate(String text) { return text.length() > 80 ? text.substring(0, 80) + "…" : text; }
}
