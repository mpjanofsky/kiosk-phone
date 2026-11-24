// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CallButtonsTest {
    @Test void parsesActionsAndIcons() {
        CallButtons b = new CallButtons(Arrays.asList("Open gate | dtmf:1 | mdi:gate-open", "", "Gong | INFO:*3#"), "");
        assertEquals(2, b.forCaller("anyone").size());
        CallButtons.Button gate = b.forCaller("x").get(0);
        assertEquals(CallButtons.Kind.DTMF, gate.kind);
        assertEquals("1", gate.target);
        assertEquals("mdi:gate-open", gate.toJson().get("icon"));
        CallButtons.Button gong = b.byId("b3");
        assertEquals(CallButtons.Kind.INFO, gong.kind);
        assertEquals("*3#", gong.target);
        assertFalse(gong.toJson().containsKey("icon"));
        assertTrue(b.problems().isEmpty());
    }

    @Test void urlKinds() {
        CallButtons b = new CallButtons(Arrays.asList("A | url:https://h/x", "B | get:http://h/y", "C | post:http://h/z"), "620");
        assertEquals(CallButtons.Kind.POST, b.byId("b1").kind);
        assertEquals(CallButtons.Kind.GET, b.byId("b2").kind);
        assertEquals(CallButtons.Kind.POST, b.byId("b3").kind);
    }

    @Test void reportsBadSettings() {
        CallButtons b = new CallButtons(Arrays.asList("no action", "X | dtmf:12x", "Y | url:ftp://h", "Z | dtmf:1 | gate"), "");
        assertTrue(b.forCaller("x").isEmpty());
        assertEquals(4, b.problems().size());
    }

    @Test void matchesCallersAndCutsLongLabels() {
        CallButtons b = new CallButtons(Arrays.asList("A very long label that is longer than forty characters | dtmf:1"), "620, Haustür");
        assertEquals(1, b.forCaller("ALP (**620)").size());
        assertEquals(1, b.forCaller("haustür").size());
        assertTrue(b.forCaller("Peter (**611)").isEmpty());
        assertEquals(40, b.byId("b1").label.length());
    }

    @Test void urlButtonsNeedCallers() {
        CallButtons b = new CallButtons(Arrays.asList("Gate | get:http://h/open", "Door | info:3"), "");
        assertNull(b.byId("b1"), "a URL button with no callers set must not exist at all");
        assertEquals(1, b.forCaller("anyone").size());
        assertEquals(CallButtons.Kind.INFO, b.forCaller("anyone").get(0).kind);
        assertEquals(1, b.problems().size());
        assertTrue(b.problems().get(0).contains("Callers with buttons"));
        assertNotNull(new CallButtons(Arrays.asList("Gate | get:http://h/open"), "**620").byId("b1"));
    }

    @Test void exactCallerMatch() {
        CallButtons b = new CallButtons(Arrays.asList("Gate | dtmf:1"), "**620");
        assertEquals(1, b.forCaller("ALP (**620)").size());
        assertTrue(b.forCaller("ALP (**6201)").isEmpty());
        assertTrue(b.forCaller("Phone (**1620)").isEmpty());
    }
}
