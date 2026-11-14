// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import kioskvoip.intercom.Call;
import kioskvoip.intercom.VideoLink;
import kioskvoip.video.RtspSource;

/**
 * The doorbell picture for one incoming call. Starts pulling the camera as soon as the INVITE arrives, so a key frame
 * is usually in hand by the time the kiosk rings; then feeds the call's video socket until the call ends. The camera
 * connection lives exactly as long as the call. Thread-safe.
 */
final class CallVideo implements RtspSource.Sink {
    /** Which callers get a picture, and from where. Immutable. */
    static final class Config {
        final String url;
        private final String[] callers;

        /** {@code callers}: comma-separated parts of the caller text (number or name); empty = every call. */
        Config(String url, String callers) {
            this.url = url == null ? "" : url.trim();
            String list = callers == null ? "" : callers.trim();
            this.callers = list.isEmpty() ? new String[0] : list.toLowerCase(Locale.ROOT).split("\\s*,\\s*");
        }

        boolean enabled() { return !url.isEmpty(); }

        /** True when this caller should get the picture. Caller text looks like {@code Name (**620)} or {@code 620}. */
        boolean wants(String caller) {
            if (!enabled()) return false;
            if (callers.length == 0) return true;
            String c = caller == null ? "" : caller.toLowerCase(Locale.ROOT);
            for (String part : callers) if (!part.isEmpty() && c.contains(part)) return true;
            return false;
        }
    }

    private final RtspSource source;
    private final Consumer<String> log;
    private volatile RtspSource.Frame lastKey;
    private volatile VideoLink link;
    private volatile boolean stopped;

    private CallVideo(RtspSource source, Consumer<String> log) {
        this.source = source;
        this.log = log;
    }

    /** Starts the camera for a call, or returns null when this caller gets none or the URL is unusable. */
    static CallVideo start(Config config, String caller, Consumer<String> log, Function<String, RtspSource> factory) {
        if (!config.wants(caller)) return null;
        RtspSource source;
        try {
            source = factory.apply(config.url);
        } catch (IllegalArgumentException bad) {
            log.accept("video: " + bad.getMessage());
            return null;
        }
        CallVideo video = new CallVideo(source, log);
        source.start(video);
        return video;
    }

    @Override public void onFrame(RtspSource.Frame frame) {
        if (frame.key) lastKey = frame;
        VideoLink current = link;
        if (current != null) {
            if (current.isClosed()) link = null;
            else current.onFrame(frame);
        }
    }

    /** Opens the call's video socket and starts feeding it; stops the camera if the kiosk takes no video. */
    void attach(Call call) {
        if (stopped) return;
        if (!call.videoAccepted()) {
            log.accept("video: the kiosk does not take video on this call");
            stop();
            return;
        }
        try {
            VideoLink opened = call.openVideo(lastKey);
            if (opened == null) { stop(); return; }
            link = opened;
            if (stopped) opened.close();
        } catch (IOException failure) {
            log.accept("video socket failed: " + failure.getMessage());
            stop();
        }
    }

    void stop() {
        stopped = true;
        source.stop();
        VideoLink current = link;
        link = null;
        if (current != null) current.close();
    }
}
