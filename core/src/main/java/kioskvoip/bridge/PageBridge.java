// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import kioskvoip.intercom.Broadcast;
import kioskvoip.intercom.Call;
import kioskvoip.intercom.IntercomClient;
import kioskvoip.sip.SipClient;

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
        void onPage(String caller, String status, int sipCode, String detail);
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
    private String currentCaller;

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
                forget(placed.get());
                attached().declineRinging(603, "Decline");
                events.onCall(caller, "declined", "declined on the kiosk");
            }
            @Override public void onEnded(String reason) {
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
        };
        IntercomClient.Outcome outcome = intercom.call(caller, listener);
        if (!outcome.started()) {
            int code = IntercomClient.BUSY.equals(outcome.status) ? 486 : 480;
            events.onPage(caller, outcome.status, code, outcome.detail + ", call, codec " + codec);
            return code == 486 ? SipClient.Answer.busy() : SipClient.Answer.unavailable();
        }
        placed.set(outcome.call);
        synchronized (this) {
            currentCall = outcome.call;
            currentCaller = caller;
        }
        events.onPage(caller, outcome.status, 180, outcome.detail + ", call, codec " + codec);
        events.onCall(caller, "ringing", "ringing on the kiosk");
        return SipClient.Answer.ring(reason -> {
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
            currentCaller = null;
        }
    }

    @Override public SipClient.Answer onInvite(String caller, String codec) {
        // SipClient sent 100 Trying already, so blocking here for the kiosk's answer is fine.
        long inviteAt = System.nanoTime();
        IntercomClient.Outcome outcome = intercom.broadcast(caller, this::kioskEnded);
        long kioskAnswerMs = elapsedMs(inviteAt);
        int code = sipCode(outcome);
        events.onPage(caller, outcome.status, code, outcome.detail + ", codec " + codec);
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
        String caller;
        synchronized (this) {
            ended = current;
            endedCall = currentCall;
            caller = currentCaller;
            current = null;
            currentCall = null;
            currentCaller = null;
        }
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
