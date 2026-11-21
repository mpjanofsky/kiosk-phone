// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.IOException;

/**
 * A call we placed to a kiosk ({@code kind:"call"}): rings until the person at the kiosk answers or declines, then
 * carries audio both ways. Created by {@link IntercomClient#call}. Thread-safe.
 */
public final class Call implements CallbackServer.Handler {
    public interface Listener {
        /** The kiosk user pressed Answer and the audio socket is open. */
        void onAnswered();
        /** The kiosk user pressed Decline. */
        void onDeclined();
        /** The call ended after it was set up or before it was answered without a decline (missed, kiosk hangup, socket loss). */
        void onEnded(String reason);
        /** 16 kHz mono PCM from the kiosk microphone, on the socket reader thread. */
        void onMic(short[] pcm16k);
        /** A tap on call-card button {@code id}; true when its action ran. */
        default boolean onButton(String id) { return false; }
    }

    private enum State { RINGING, ACTIVE, DONE }

    private final IntercomClient client;
    private final CallbackServer server;
    private volatile int port;
    private final String id;
    private final Listener listener;
    private State state = State.RINGING;
    private Broadcast audio;
    private volatile boolean videoAccepted;
    private VideoLink video;

    Call(IntercomClient client, CallbackServer server, int port, String id, Listener listener) {
        this.client = client;
        this.server = server;
        this.port = port;
        this.id = id;
        this.listener = listener;
        server.register(id, this);
    }

    void setPort(int port) { this.port = port; }

    void setVideoAccepted(boolean accepted) { videoAccepted = accepted; }

    /** The kiosk said it shows video on this call. */
    public boolean videoAccepted() { return videoAccepted; }

    /**
     * Opens the video socket (caller side, while it rings or once answered) and returns it to be fed frames; null when
     * the kiosk does not take video or the call is over. {@code lastKey}: a recent key frame to show straight away.
     */
    public VideoLink openVideo(kioskvoip.video.RtspSource.Frame lastKey) throws IOException {
        synchronized (this) {
            if (!videoAccepted || state == State.DONE) return null;
            if (video != null && !video.isClosed()) return video;
        }
        VideoLink link = new VideoLink(client, port, id);
        link.open(lastKey);
        synchronized (this) {
            if (state == State.DONE) { link.close(); return null; }
            video = link;
        }
        return link;
    }

    private void closeVideo() {
        VideoLink link;
        synchronized (this) { link = video; video = null; }
        if (link != null) link.close();
    }

    /** Drops the callback registration of a call that never started. */
    void discard() {
        synchronized (this) { state = State.DONE; }
        server.unregister(id);
        closeVideo();
    }

    public String callId() { return id; }

    public synchronized boolean isActive() { return state == State.ACTIVE; }

    /** Caller audio towards the kiosk speaker (16 kHz PCM, any chunk size). Ignored until answered. */
    public void write(short[] pcm16k) {
        Broadcast current;
        synchronized (this) { current = state == State.ACTIVE ? audio : null; }
        if (current != null) current.write(pcm16k);
    }

    /** Stops ringing (the caller gave up). No effect once answered or over. */
    public void cancel() {
        synchronized (this) {
            if (state != State.RINGING) return;
            state = State.DONE;
        }
        server.unregister(id);
        closeVideo();
        client.hangup(port, id, "cancel");
    }

    /** Ends an answered call (the caller hung up); a ringing call is cancelled instead. */
    public void end() {
        Broadcast current;
        synchronized (this) {
            if (state == State.RINGING) { cancel(); return; }
            if (state != State.ACTIVE) return;
            state = State.DONE;
            current = audio;
        }
        server.unregister(id);
        closeVideo();
        current.end();
    }

    @Override public void onAction(String action) {
        switch (action) {
            case "answer": answered(); break;
            case "decline":
                if (finish(State.RINGING)) listener.onDeclined();
                break;
            case "missed": case "hangup": case "cancel":
                if (finishAny()) {
                    Broadcast open;
                    synchronized (this) { open = audio; }
                    if (open != null) open.end();
                    listener.onEnded("kiosk reported " + action);
                }
                break;
            default:
                client.log("call " + IntercomClient.shortId(id) + " ignoring unknown action");
        }
    }

    @Override public boolean onCustom(String id) {
        synchronized (this) {
            if (state == State.DONE) return false;
        }
        return listener.onButton(id);
    }

    private void answered() {
        synchronized (this) {
            if (state != State.RINGING) return;
        }
        Broadcast opened = new Broadcast(client, port, id, this::kioskEnded, listener::onMic);
        try {
            opened.open();
        } catch (IOException failure) {
            if (finish(State.RINGING)) {
                client.hangup(port, id, "hangup");
                listener.onEnded("audio socket failed: " + failure.getMessage());
            }
            return;
        }
        synchronized (this) {
            if (state != State.RINGING) { opened.end(); return; }
            state = State.ACTIVE;
            audio = opened;
        }
        listener.onAnswered();
    }

    private void kioskEnded() {
        if (finish(State.ACTIVE)) listener.onEnded("kiosk ended the call");
    }

    private boolean finish(State expected) {
        synchronized (this) {
            if (state != expected) return false;
            state = State.DONE;
        }
        server.unregister(id);
        closeVideo();
        return true;
    }

    private boolean finishAny() {
        synchronized (this) {
            if (state == State.DONE) return false;
            state = State.DONE;
        }
        server.unregister(id);
        closeVideo();
        return true;
    }
}
