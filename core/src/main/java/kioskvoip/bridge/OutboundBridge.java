// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import kioskvoip.sip.RtpSession;
import kioskvoip.sip.SipClient;

/**
 * Carries one outgoing call: a person at the kiosk taps a phone-book entry (the kiosk's intercom call to our virtual
 * peer), the bridge dials that number through {@link SipClient}, and tells the kiosk how it went. The kiosk side is
 * a {@link Leg} so this logic is tested without the intercom transport; the transport calls {@link #kioskMic} and
 * {@link #kioskEnded} for what the kiosk does. One call at a time (the SipClient's), like {@link PageBridge}.
 */
public final class OutboundBridge {
    /** The kiosk's half of the call (the intercom call it placed to the virtual peer). Calls may block briefly on HTTP. */
    public interface Leg {
        /** The far end answered: complete the kiosk's call (the kiosk then opens the audio socket). */
        void answered();
        /** The call is over without the kiosk asking: {@code decline} (busy, refused), {@code missed} (no answer, unreachable) or {@code hangup}. */
        void end(String outcome);
        /** Far-end audio for the kiosk speaker (16 kHz mono PCM). */
        void write(short[] pcm16k);
    }

    /** What the host app hears about. {@code state}: ringing, answered, busy, no answer, declined, failed, cancelled, ended. */
    public interface Events {
        void onOutgoing(String who, String state, String detail);
        void log(String message);
    }

    private final SipClient sip;
    private final Events events;
    private volatile double micGain = 1.0;
    private Session current;

    private static final class Session {
        final PhoneBook.Entry entry;
        final Leg leg;
        boolean answered, kioskEnded, done;

        Session(PhoneBook.Entry entry, Leg leg) {
            this.entry = entry;
            this.leg = leg;
        }

        String who() { return entry.name + " (" + entry.number + ")"; }
    }

    public OutboundBridge(SipClient sip, Events events) {
        this.sip = sip;
        this.events = events;
    }

    /** Gain applied to kiosk microphone audio sent to the far end; 1 = unchanged. */
    public void setMicGain(double gain) { micGain = Math.max(1.0, Math.min(30.0, gain)); }

    /**
     * Dials {@code entry}. Returns null when the INVITE went out (the leg is then told how it ends), else why not:
     * the transport answers the kiosk {@code busy} or {@code off}-like and nothing is recorded as a call.
     */
    public String place(PhoneBook.Entry entry, Leg leg) {
        Session session = new Session(entry, leg);
        synchronized (this) {
            if (current != null) return "Busy";
            current = session;
        }
        String problem = sip.placeCall(entry.number, handlerFor(session), pcm -> leg.write(pcm));
        if (problem != null) {
            synchronized (this) { if (current == session) current = null; }
            events.log("outgoing call to " + session.who() + " not placed: " + problem);
            return problem;
        }
        events.log("outgoing call to " + session.who() + ": INVITE sent");
        return null;
    }

    /** Kiosk microphone audio towards the far end. */
    public void kioskMic(short[] pcm16k) {
        double gain = micGain;
        sip.sendAudio(gain == 1.0 ? pcm16k : PageBridge.applyGain(pcm16k, gain));
    }

    /** The kiosk gave up: it cancelled while ringing, hung up, or its audio socket closed. Ends the SIP call whichever state it is in. */
    public void kioskEnded(String reason) {
        Session session;
        synchronized (this) {
            session = current;
            if (session == null || session.done) return;
            session.kioskEnded = true;
        }
        events.log("outgoing call to " + session.who() + ": kiosk ended it (" + reason + ")");
        if (!sip.cancelCall()) sip.hangup();
    }

    private SipClient.OutgoingHandler handlerFor(Session session) {
        return new SipClient.OutgoingHandler() {
            @Override public void onRinging() {
                if (!live(session)) return;
                events.onOutgoing(session.who(), "ringing", "far end is ringing");
            }

            @Override public void onAnswered(String codec) {
                synchronized (OutboundBridge.this) {
                    if (current != session || session.kioskEnded) return; // kioskEnded: its hangup follows
                    session.answered = true;
                }
                events.onOutgoing(session.who(), "answered", codec);
                session.leg.answered();
            }

            @Override public void onFailed(int code, String reason) {
                boolean tellKiosk;
                synchronized (OutboundBridge.this) {
                    if (current != session) return;
                    current = null;
                    session.done = true;
                    tellKiosk = !session.kioskEnded;
                }
                String state, outcome;
                if (session.kioskEnded || code == 487) {
                    state = "cancelled";
                    outcome = null;
                } else if (code == 486 || code == 600) {
                    state = "busy";
                    outcome = "decline";
                } else if (code == 603) {
                    state = "declined";
                    outcome = "decline";
                } else if (code == 408 || code == 480) {
                    state = "no answer";
                    outcome = "missed";
                } else {
                    state = "failed";
                    outcome = "decline";
                }
                events.onOutgoing(session.who(), state, code + " " + reason);
                if (tellKiosk && outcome != null) session.leg.end(outcome);
            }

            @Override public void onEnded(String reason, long packets, long dropped) {
                boolean tellKiosk;
                synchronized (OutboundBridge.this) {
                    if (current != session) return;
                    current = null;
                    session.done = true;
                    tellKiosk = !session.kioskEnded;
                }
                events.onOutgoing(session.who(), "ended", String.format("%s; %d RTP packets, %d lost", reason, packets, dropped));
                if (tellKiosk) session.leg.end("hangup");
            }
        };
    }

    private synchronized boolean live(Session session) { return current == session && !session.done; }
}
