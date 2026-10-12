// SPDX-License-Identifier: Apache-2.0
package kioskvoip.spike;

import kioskvoip.bridge.PageBridge;
import kioskvoip.sip.SipClient;

/**
 * Spike: register one extension and play every page on one kiosk through PageBridge.
 * Env: SIP_SERVER, SIP_USER, SIP_PASSWORD, optional SIP_AUTH_USER, SIP_PORT (5060), SIP_LOCAL_PORT (5070); plus KioskSetup.
 */
public final class PageToKiosk {
    public static void main(String[] args) throws Exception {
        String user = KioskSetup.env("SIP_USER", null);
        SipClient.Config config = new SipClient.Config(
            KioskSetup.env("SIP_SERVER", null), Integer.parseInt(KioskSetup.env("SIP_PORT", "5060")), user,
            KioskSetup.env("SIP_AUTH_USER", ""), KioskSetup.env("SIP_PASSWORD", null),
            Integer.parseInt(KioskSetup.env("SIP_LOCAL_PORT", "5070")), 300);
        PageBridge bridge = new PageBridge(KioskSetup.client(user), new PageBridge.Events() {
            @Override public void onRegistration(boolean registered, String detail) {
                KioskSetup.event((registered ? "registered: " : "not registered: ") + detail);
            }
            @Override public void onPage(String caller, String status, int sipCode, String detail) {
                KioskSetup.event("page from " + caller + ": kiosk " + status + " → SIP " + sipCode + " (" + detail + ")");
            }
            @Override public void onPageEnded(String caller, String reason) { KioskSetup.event("page from " + caller + " ended: " + reason); }
            @Override public void log(String message) { KioskSetup.event(message); }
        });
        SipClient client = new SipClient(config, bridge);
        bridge.attach(client);
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
        client.start();
        KioskSetup.event("started; Ctrl-C to stop");
        Thread.currentThread().join();
    }
}
