package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class LastCallTest {
    private final LastCall log = new LastCall(() -> 0L);

    private static String time() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(0L));
    }

    @Test
    void refusalsUseFixedWording() {
        String t = time();
        assertEquals("incoming · A (1) · blocked: Do Not Disturb · G.722 · " + t, log.onPage("A (1)", "dnd", 480, "G722"));
        assertEquals("incoming · A (1) · blocked: intercom key mismatch · G.722 · " + t, log.onPage("A (1)", "key", 480, "G722"));
        assertEquals("incoming · A (1) · blocked: intercom off · G.722 · " + t, log.onPage("A (1)", "off", 480, "G722"));
        assertEquals("incoming · A (1) · busy · G.722 · " + t, log.onPage("A (1)", "busy", 486, "G722"));
        assertEquals("incoming · A (1) · failed: call setup error · G.722 · " + t, log.onPage("A (1)", "error", 480, "G722"));
        assertEquals("incoming · A (1) · failed: call setup error · G.722 · " + t, log.onPage("A (1)", "weird", 480, "G722"));
    }

    @Test
    void pagePlayedOnSuccess() {
        assertTrue(log.onPage("B", "listening", 200, "G722").contains("page played"));
    }

    @Test
    void ringingCallRecordsNothingUntilItEnds() {
        assertNull(log.onPage("C", "ringing", 180, "G722"));
        assertNull(log.onCall("C", "ringing"));
        assertTrue(log.onCall("C", "cancelled").contains("missed"));
    }

    @Test
    void answeredCallIsLoggedOnceAndItsEndIsNot() {
        log.onPage("D", "ringing", 180, "G722");
        assertTrue(log.onCall("D", "answered").contains("answered"));
        assertNull(log.onCall("D", "ended"));
        // the next call starts clean: unanswered end is a miss again
        log.onPage("D", "ringing", 180, "G722");
        assertTrue(log.onCall("D", "ended").contains("missed"));
    }

    @Test
    void declinedIsRecorded() {
        log.onPage("E", "ringing", 180, "G722");
        assertTrue(log.onCall("E", "declined").contains("declined"));
    }

    @Test
    void longCallerNamesAreShortened() {
        String name = "x".repeat(200);
        String entry = log.onPage(name, "dnd", 480, "G722");
        assertTrue(entry.length() < 140, entry);
        assertTrue(entry.contains("…"));
    }

    @Test
    void codecIsShownAndG711IsNamedAsSuch() {
        assertTrue(log.onPage("F", "listening", 200, "PCMU").contains("page played · G.711 µ-law · "));
        assertTrue(log.onPage("F", "listening", 200, "PCMA").contains("G.711 A-law"));
        assertTrue(log.onPage("F", "listening", 200, "PT96").contains("PT96"));
        assertFalse(log.onPage("F", "listening", 200, null).contains("G."));
    }

    @Test
    void callEntriesReuseTheCodecOfTheirInvite() {
        log.onPage("G", "ringing", 180, "PCMA");
        assertTrue(log.onCall("G", "answered").contains("answered · G.711 A-law · "));
        log.onPage("G", "ringing", 180, "G722");
        assertTrue(log.onCall("G", "ended").contains("missed · G.722 · "));
    }
}
