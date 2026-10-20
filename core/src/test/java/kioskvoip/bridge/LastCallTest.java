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
        assertEquals("incoming · A (1) · blocked: Do Not Disturb · " + t, log.onPage("A (1)", "dnd", 480));
        assertEquals("incoming · A (1) · blocked: intercom key mismatch · " + t, log.onPage("A (1)", "key", 480));
        assertEquals("incoming · A (1) · blocked: intercom off · " + t, log.onPage("A (1)", "off", 480));
        assertEquals("incoming · A (1) · busy · " + t, log.onPage("A (1)", "busy", 486));
        assertEquals("incoming · A (1) · failed: call setup error · " + t, log.onPage("A (1)", "error", 480));
        assertEquals("incoming · A (1) · failed: call setup error · " + t, log.onPage("A (1)", "weird", 480));
    }

    @Test
    void pagePlayedOnSuccess() {
        assertTrue(log.onPage("B", "listening", 200).contains("page played"));
    }

    @Test
    void ringingCallRecordsNothingUntilItEnds() {
        assertNull(log.onPage("C", "ringing", 180));
        assertNull(log.onCall("C", "ringing"));
        assertTrue(log.onCall("C", "cancelled").contains("missed"));
    }

    @Test
    void answeredCallIsLoggedOnceAndItsEndIsNot() {
        log.onPage("D", "ringing", 180);
        assertTrue(log.onCall("D", "answered").contains("answered"));
        assertNull(log.onCall("D", "ended"));
        // the next call starts clean: unanswered end is a miss again
        log.onPage("D", "ringing", 180);
        assertTrue(log.onCall("D", "ended").contains("missed"));
    }

    @Test
    void declinedIsRecorded() {
        log.onPage("E", "ringing", 180);
        assertTrue(log.onCall("E", "declined").contains("declined"));
    }

    @Test
    void longCallerNamesAreShortened() {
        String name = "x".repeat(200);
        String entry = log.onPage(name, "dnd", 480);
        assertTrue(entry.length() < 140, entry);
        assertTrue(entry.contains("…"));
    }
}
