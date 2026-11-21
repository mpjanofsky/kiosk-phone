// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import kioskvoip.intercom.Broadcast;
import kioskvoip.intercom.Call;
import kioskvoip.intercom.IntercomClient;
import kioskvoip.sip.SipClient;
import kioskvoip.video.RtspSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges SIP to one kiosk. A page (auto-answer INVITE) plays as an intercom broadcast; an ordinary call rings the
 * kiosk and, when the person there presses Answer, carries audio both ways (needs the client's callback server).
 * The rest of this comment describes pages.
 *
 * Plays each SIP page on one kiosk as an intercom broadcast. The kiosk's answer decides the SIP reply: a started
 * broadcast → 200, {@code busy} → 486, anything else (DND, off, refused, unreachable, key/TLS problems) → 480.
 * Refusals are logged; no retries. The kiosk ending the broadcast (Dismiss) hangs up the SIP call.
 */
public final class PageBridge implements SipClient.Listener {
    /** What the host app (spike, plugin) hears about. */
    public interface Events {
        void onRegistration(boolean registered, String detail);
        /** {@code status}: the intercom status; {@code sipCode}: what the PBX was told. */
        void onPage(String caller, String status, int sipCode, String codec, String detail);
        void onPageEnded(String caller, String reason);
        /** A call's progress: {@code ringing}, {@code answered}, {@code declined}, {@code cancelled} or {@code ended}. */
        default void onCall(String caller, String state, String detail) { }
        /** First caller audio reached the kiosk: ms from INVITE to the kiosk's answer and to that first packet. */
        default void onFirstAudio(String caller, long kioskAnswerMs, long firstAudioMs) { }
        void log(String message);
    }

    private final IntercomClient intercom;
    private final Events events;
    private volatile double micGain = 1.0;
    private volatile SipClient sip;
    private Broadcast current;
    private Call currentCall;
    private CallVideo currentVideo;
    private String currentCaller;
    private volatile CallVideo.Config video = new CallVideo.Config("", "");
    private volatile java.util.function.Function<String, RtspSource> videoSources = url -> new RtspSource(url, this::log);

    public PageBridge(IntercomClient intercom, Events events) {
        this.intercom = intercom;
        this.events = events;
    }

    /** Gain applied to kiosk microphone audio sent to a caller (the Echo's mic is quiet); 1 = unchanged. */
    public void setMicGain(double gain) { micGain = Math.max(1.0, Math.min(30.0, gain)); }

    /** Linear gain with a soft knee above 24000 so loud peaks compress instead of clipping hard. Returns a new array. */
    static short[] applyGain(short[] pcm, double gain) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            double y = pcm[i] * gain, a = Math.abs(y);
            if (a > 24000) a = Math.min(32767, 24000 + (a - 24000) * 0.25);
            out[i] = (short) Math.round(Math.copySign(a, y));
        }
        return out;
    }

    /**
     * Doorbell picture: calls from a matching caller ring the kiosk with video pulled from {@code url} (rtsp://).
     * {@code callers}: comma-separated parts of the caller text, empty = all calls; an empty URL turns it off.
     */
    public void setVideo(String url, String callers) { video = new CallVideo.Config(url, callers); }

    private volatile CallButtons buttons = CallButtons.none();
    private volatile ButtonRunner urlRunner = PageBridge::callUrl;

    /** Buttons on the kiosk's call card for matching callers (KS 2026.11.24+). */
    public void setButtons(CallButtons buttons) { this.buttons = buttons == null ? CallButtons.none() : buttons; }

    /** Runs a URL button; returns null on success or why it failed. Tests swap it. */
    interface ButtonRunner { String run(CallButtons.Button button); }

    void setUrlRunner(ButtonRunner runner) { urlRunner = runner; }

    /** POST (empty body) or GET with 5 s timeouts; any 2xx is success. */
    static String callUrl(CallButtons.Button button) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(button.target).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            if (button.kind == CallButtons.Kind.POST) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300 ? null : "HTTP " + code;
        } catch (java.io.IOException | RuntimeException failure) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** One tap on a call-card button during a call from {@code caller}. */
    boolean runButton(String caller, String id) {
        CallButtons.Button button = buttons.byId(id);
        if (button == null) {
            events.log("button " + id + " from " + caller + ": not configured (any more)");
            return false;
        }
        String problem;
        switch (button.kind) {
            case DTMF: problem = attached().sendDtmf(button.target, false); break;
            case INFO: problem = attached().sendDtmf(button.target, true); break;
            default: problem = urlRunner.run(button); break;
        }
        events.log("button '" + button.label + "' (" + caller + "): " + (problem == null ? "done" : "failed: " + problem));
        return problem == null;
    }

    /** Tests swap the camera for a fake one. */
    void setVideoSources(java.util.function.Function<String, RtspSource> factory) { videoSources = factory; }

    /** The SipClient this bridge listens to, so a kiosk-side Dismiss can hang up. */
    public void attach(SipClient sip) { this.sip = sip; }

    /** SIP status for an intercom outcome; 200 only when audio can flow. */
    static int sipCode(IntercomClient.Outcome outcome) {
        if (outcome.started()) return 200;
        return IntercomClient.BUSY.equals(outcome.status) ? 486 : 480;
    }

    @Override public void onRegistration(boolean registered, String detail) { events.onRegistration(registered, detail); }

    /** FreePBX pages carry auto-answer hints (docs/pbx.md); ordinary calls do not. */
    static boolean isPage(String alertInfo, String callInfo) {
        return (alertInfo != null && alertInfo.toLowerCase().contains("ring answer"))
            || (callInfo != null && callInfo.toLowerCase().contains("answer-after"));
    }

    @Override public SipClient.Answer onInvite(String caller, String codec, String alertInfo, String callInfo) {
        return isPage(alertInfo, callInfo) ? onInvite(caller, codec) : onCallInvite(caller, codec);
    }

    private SipClient.Answer onCallInvite(String caller, String codec) {
        AtomicReference<Call> placed = new AtomicReference<>();
        // The camera starts with the INVITE, so a key frame is usually in hand by the time the kiosk rings.
        CallVideo picture = CallVideo.start(video, caller, events::log, videoSources);
        Call.Listener listener = new Call.Listener() {
            @Override public void onAnswered() {
                Call call = placed.get();
                if (!attached().acceptRinging(call::write)) {
                    call.end(); // the caller gave up while the kiosk was answering
                    return;
                }
                events.onCall(caller, "answered", "answered on the kiosk");
            }
            @Override public void onDeclined() {
                if (picture != null) picture.stop();
                forget(placed.get());
                attached().declineRinging(603, "Decline");
                events.onCall(caller, "declined", "declined on the kiosk");
            }
            @Override public void onEnded(String reason) {
                if (picture != null) picture.stop();
                SipClient client = attached();
                forget(placed.get());
                // Still ringing: the kiosk gave up (missed). Answered: hang up the caller.
                if (!client.declineRinging(480, "Temporarily Unavailable")) client.hangup();
                events.onCall(caller, "ended", reason);
            }
            @Override public void onMic(short[] pcm) {
                double gain = micGain;
                attached().sendAudio(gain == 1.0 ? pcm : applyGain(pcm, gain));
            }
            @Override public boolean onButton(String id) { return runButton(caller, id); }
        };
        java.util.List<java.util.Map<String, Object>> actions = new java.util.ArrayList<>();
        for (CallButtons.Button b : buttons.forCaller(caller)) actions.add(b.toJson());
        IntercomClient.Outcome outcome = intercom.call(caller, listener, picture != null, actions);
        if (!outcome.started()) {
            if (picture != null) picture.stop();
            int code = IntercomClient.BUSY.equals(outcome.status) ? 486 : 480;
            events.onPage(caller, outcome.status, code, codec, outcome.detail + ", call, codec " + codec);
            return code == 486 ? SipClient.Answer.busy() : SipClient.Answer.unavailable();
        }
        placed.set(outcome.call);
        synchronized (this) {
            currentCall = outcome.call;
            currentVideo = picture;
            currentCaller = caller;
        }
        if (picture != null) {
            // Opening the video socket can take a moment; the 180 Ringing should not wait for it.
            Thread t = new Thread(() -> picture.attach(outcome.call), "call-video");
            t.setDaemon(true);
            t.start();
        }
        events.onPage(caller, outcome.status, 180, codec, outcome.detail + ", call, codec " + codec);
        events.onCall(caller, "ringing", "ringing on the kiosk");
        return SipClient.Answer.ring(reason -> {
            if (picture != null) picture.stop();
            outcome.call.cancel();
            forget(outcome.call);
            events.onCall(caller, "cancelled", reason);
        });
    }

    private SipClient attached() {
        SipClient client = sip;
        if (client == null) throw new IllegalStateException("PageBridge.attach() was not called");
        return client;
    }

    private synchronized void forget(Call call) {
        if (currentCall == call) {
            currentCall = null;
            currentVideo = null;
            currentCaller = null;
        }
    }

    @Override public SipClient.Answer onInvite(String caller, String codec) {
        // SipClient sent 100 Trying already, so blocking here for the kiosk's answer is fine.
        long inviteAt = System.nanoTime();
        IntercomClient.Outcome outcome = intercom.broadcast(caller, this::kioskEnded);
        long kioskAnswerMs = elapsedMs(inviteAt);
        int code = sipCode(outcome);
        events.onPage(caller, outcome.status, code, codec, outcome.detail + ", codec " + codec);
        if (code == 486) return SipClient.Answer.busy();
        if (code != 200) return SipClient.Answer.unavailable();
        Broadcast broadcast = outcome.broadcast;
        synchronized (this) {
            current = broadcast;
            currentCaller = caller;
        }
        // Latency probe: the first RTP packet marks when caller audio reaches the kiosk (INVITE → kiosk answer → 200 →
        // ACK → first packet). The PBX's own ~0.5 s page beep and the kiosk chime come on top; they are not measured here.
        AtomicBoolean first = new AtomicBoolean(true);
        return SipClient.Answer.accept(pcm -> {
            if (first.compareAndSet(true, false)) {
                long firstAudioMs = elapsedMs(inviteAt);
                events.log(String.format("page timing: kiosk answered %d ms, first audio %d ms after INVITE", kioskAnswerMs, firstAudioMs));
                events.onFirstAudio(caller, kioskAnswerMs, firstAudioMs);
            }
            broadcast.write(pcm);
        });
    }

    private static long elapsedMs(long sinceNanos) { return (System.nanoTime() - sinceNanos) / 1_000_000; }

    @Override public void onCallEnded(String reason, long packets, long dropped) {
        Broadcast ended;
        Call endedCall;
        CallVideo endedVideo;
        String caller;
        synchronized (this) {
            ended = current;
            endedCall = currentCall;
            endedVideo = currentVideo;
            caller = currentCaller;
            current = null;
            currentCall = null;
            currentVideo = null;
            currentCaller = null;
        }
        if (endedVideo != null) endedVideo.stop();
        if (endedCall != null) {
            endedCall.end();
            events.onCall(caller, "ended", String.format("%s; %d RTP packets, %d lost", reason, packets, dropped));
            return;
        }
        if (ended == null) return;
        ended.end();
        events.onPageEnded(caller, String.format("%s; %d RTP packets, %d lost", reason, packets, dropped));
    }

    @Override public void log(String message) { events.log(message); }

    private void kioskEnded() {
        SipClient client = sip;
        if (client != null && client.hangup()) events.log("kiosk ended the broadcast; sent BYE");
    }
}
