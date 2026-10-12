package kioskvoip.intercom;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import kioskvoip.bridge.PageBridge;
import kioskvoip.sip.SipClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PageBridge against the fake kiosk (lives here to reuse the package-private FakeKiosk). */
class PageBridgeTest {
    FakeKiosk kiosk;
    PageBridge bridge;
    final BlockingQueue<String> pages = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        kiosk = new FakeKiosk(IntercomClientTest.KEY);
        IntercomClient intercom = new IntercomClient("127.0.0.1", kiosk.adminPort(), IntercomClientTest.KEY,
            IntercomClientTest.SELF, m -> { });
        bridge = new PageBridge(intercom, new PageBridge.Events() {
            public void onRegistration(boolean registered, String detail) { }
            public void onPage(String caller, String status, int sipCode, String detail) { pages.add(status + " " + sipCode); }
            public void onPageEnded(String caller, String reason) { pages.add("ended " + caller); }
            public void log(String message) { }
        });
    }

    @AfterEach
    void tearDown() throws Exception { kiosk.close(); }

    @Test
    void pageIsBroadcastAndEnded() throws Exception {
        SipClient.Answer answer = bridge.onInvite("Office", "G722");
        assertNotNull(answer);
        assertEquals("listening 200", pages.poll(2, TimeUnit.SECONDS));
        bridge.onCallEnded("BYE", 50, 0);
        assertEquals("ended Office", pages.poll(2, TimeUnit.SECONDS));
        assertEquals("call broadcast Office", kiosk.events.poll(2, TimeUnit.SECONDS));
        String last = null;
        for (String e; (e = kiosk.events.poll(2, TimeUnit.SECONDS)) != null; ) last = e;
        assertEquals("action hangup", last);
    }

    @Test
    void refusalsMapToSipCodes() throws Exception {
        kiosk.callStatus = "busy";
        bridge.onInvite("Office", "PCMU");
        assertEquals("busy 486", pages.poll(2, TimeUnit.SECONDS));
        Thread.sleep(1200);
        kiosk.dnd = true;
        bridge.onInvite("Office", "PCMU");
        assertEquals("dnd 480", pages.poll(2, TimeUnit.SECONDS));
        Thread.sleep(1200);
        kiosk.dnd = false;
        kiosk.callStatus = "refused";
        bridge.onInvite("Office", "PCMU");
        assertEquals("refused 480", pages.poll(2, TimeUnit.SECONDS));
        bridge.onCallEnded("BYE", 0, 0); // no page running: nothing happens
        assertNull(pages.poll(300, TimeUnit.MILLISECONDS));
    }
}
