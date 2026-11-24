// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CallerMatchTest {
    @Test void emptyOrStarMatchesEveryone() {
        assertTrue(new CallerMatch("").everyone());
        assertTrue(new CallerMatch(null).matches("Anyone (123)"));
        assertTrue(new CallerMatch(" , ").everyone());
        assertFalse(new CallerMatch("*").everyone());
        assertTrue(new CallerMatch("*").matches("Anyone (123)"));
    }

    @Test void fritzBoxNumberAsWritten() {
        CallerMatch m = new CallerMatch("**620");
        assertTrue(m.matches("ALP-600 (**620)"));
        assertTrue(m.matches("**620"));
        assertFalse(m.matches("ALP-600 (**6201)"));
        assertFalse(m.matches("Phone (**1620)"), "leading * on a number are part of it, not a wildcard");
        assertFalse(m.matches("Phone (1620)"));
    }

    @Test void leadingStarsIgnoredBothWays() {
        assertTrue(new CallerMatch("620").matches("ALP-600 (**620)"), "an existing 620 keeps matching **620");
        assertTrue(new CallerMatch("**620").matches("620"));
        assertFalse(new CallerMatch("620").matches("Phone (**6200)"));
        assertFalse(new CallerMatch("62").matches("ALP-600 (**620)"), "no more partial match without a wildcard");
    }

    @Test void nameContainsWildcard() {
        CallerMatch m = new CallerMatch("*R20K*");
        assertTrue(m.matches("Akuvox R20K Gate (1001)"));
        assertTrue(m.matches("r20k"));
        assertFalse(m.matches("Akuvox E12 (1002)"));
    }

    @Test void numberPrefixWildcard() {
        CallerMatch m = new CallerMatch("0611*");
        assertTrue(m.matches("Office (061112345)"));
        assertTrue(m.matches("0611"));
        assertFalse(m.matches("Office (0612345)"));
        assertTrue(new CallerMatch("**62*").matches("Door (**625)"));
        assertFalse(new CallerMatch("**62*").matches("Door (**615)"));
    }

    @Test void nameEndsWithWildcard() {
        assertTrue(new CallerMatch("*door").matches("Front Door (**620)"));
        assertFalse(new CallerMatch("*door").matches("Door bell (**620)"));
    }

    @Test void exactNameIgnoringCase() {
        CallerMatch m = new CallerMatch("Haustür, 1001");
        assertTrue(m.matches("haustür (**620)"));
        assertTrue(m.matches("Haustür"));
        assertTrue(m.matches("Gate (1001)"));
        assertFalse(m.matches("Haustür hinten (**621)"));
        assertFalse(m.matches("Gate (10011)"));
    }

    @Test void listOfEntries() {
        CallerMatch m = new CallerMatch(" **620 , *R20K* ,0611* ");
        assertTrue(m.matches("ALP (**620)"));
        assertTrue(m.matches("Akuvox R20K (1001)"));
        assertTrue(m.matches("Office (06111)"));
        assertFalse(m.matches("Peter (**611)"));
    }
}
