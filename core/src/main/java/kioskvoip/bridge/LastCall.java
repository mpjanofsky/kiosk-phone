package kioskvoip.bridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Turns bridge events into the one-line "last call" entry (a phone's call log row): {@code incoming · who · result · codec · time}.
 * Pure text and one flag, so it is unit-tested without a kiosk. Home Assistant's own history is the call log; this is
 * only the latest row, with a time on every entry so two identical results still change the state.
 */
public final class LastCall {
    private static final int WHO_MAX = 60;

    private final LongSupplier clock;
    private boolean answered;
    private String codec = "";

    public LastCall() { this(System::currentTimeMillis); }

    LastCall(LongSupplier clock) { this.clock = clock; }

    /**
     * An entry for a page or for a refused call, or null when there is nothing to record (a call that started ringing).
     * {@code codec} is the negotiated SDP codec name (PCMU, PCMA, G722); it is shown in the entry, and the later
     * {@link #onCall} entries of the same call reuse it.
     */
    public synchronized String onPage(String caller, String status, int sipCode, String codec) {
        this.codec = codecLabel(codec);
        if (sipCode == 180) {
            answered = false; // a call is now ringing; onCall reports how it ends
            return null;
        }
        if (sipCode == 200) return entry(caller, "page played");
        return entry(caller, refusal(status));
    }

    /** An entry when a call ends unanswered, is declined or is answered; null for the other states. */
    public synchronized String onCall(String caller, String state) {
        switch (state) {
            case "answered":
                answered = true;
                return entry(caller, "answered");
            case "declined":
                return entry(caller, "declined");
            case "cancelled":
            case "ended":
                if (answered) { answered = false; return null; }
                return entry(caller, "missed");
            default:
                return null;
        }
    }

    /** Plain label for the codec; G.711 is the narrowband one (4 kHz), which is why it matters when audio sounds muffled. */
    static String codecLabel(String name) {
        if (name == null) return "";
        switch (name) {
            case "G722": return "G.722";
            case "PCMU": return "G.711 µ-law";
            case "PCMA": return "G.711 A-law";
            default: return name.isEmpty() ? "" : name;
        }
    }

    /** Fixed wording for the intercom statuses; the raw detail stays in the log. */
    static String refusal(String status) {
        if (status == null) return "failed: call setup error";
        switch (status) {
            case "dnd": return "blocked: Do Not Disturb";
            case "off": return "blocked: intercom off";
            case "key": return "blocked: intercom key mismatch";
            case "tls": return "blocked: KS requires encryption";
            case "refused": return "blocked: refused by Kiosk Satellite";
            case "busy": return "busy";
            case "unreachable": return "failed: Kiosk Satellite not reachable";
            default: return "failed: call setup error";
        }
    }

    private String entry(String caller, String result) {
        String who = caller == null || caller.isEmpty() ? "unknown" : caller;
        if (who.length() > WHO_MAX) who = who.substring(0, WHO_MAX - 1) + "…";
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(clock.getAsLong()));
        String used = codec.isEmpty() ? "" : codec + " · ";
        return "incoming · " + who + " · " + result + " · " + used + time;
    }
}
