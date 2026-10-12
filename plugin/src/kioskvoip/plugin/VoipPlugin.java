// SPDX-License-Identifier: Apache-2.0
package kioskvoip.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import kioskvoip.bridge.PageBridge;
import kioskvoip.intercom.CallbackServer;
import kioskvoip.intercom.IntercomClient;
import kioskvoip.sip.SipClient;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Thin KS wrapper: settings → SipClient + PageBridge → this kiosk's own intercom at 127.0.0.1.
 * All network work is on core's own threads; callbacks here only swap the session and return.
 */
public final class VoipPlugin implements KioskPlugin {
    private static final String VERSION = "0.5.0";

    private PluginHost host;
    private SipClient sip;
    private CallbackServer callbacks;
    private Map<String, Object> applied;

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        host.publishBinarySensor("registered", "Registered", "connectivity", false);
        host.publishTextSensor("last_page", "Last page", "none yet");
        host.publishTextSensor("status", "Status", "starting");
        host.publishTextSensor("call_state", "Call state", "idle");
        open(settings);
    }

    @Override public synchronized void configure(Map<String, Object> settings) {
        if (settings.equals(applied)) return; // KS sends the full settings on every edit
        close();
        open(settings);
    }

    @Override public void execute(String command, Map<String, Object> arguments) { }

    @Override public void onEvent(String event, Map<String, Object> payload) { }

    @Override public synchronized void stop() {
        close();
        if (host != null) {
            host.removeBinarySensor("registered");
            host.removeTextSensor("last_page");
            host.removeTextSensor("status");
            host.removeTextSensor("call_state");
        }
        host = null;
    }

    private void open(Map<String, Object> s) {
        applied = s;
        PluginHost h = host;
        String extension = text(s, "extension");
        SipClient.Config config = new SipClient.Config(text(s, "pbxHost"), number(s, "pbxPort"), extension,
            text(s, "authUser"), text(s, "password"), number(s, "localPort"), 300);
        String problem = config.problem();
        if (problem == null && text(s, "intercomKey").isEmpty()) problem = "No intercom key configured";
        if (problem != null) {
            h.status(problem, true);
            h.publishTextSensor("status", "Status", problem);
            h.publishBinarySensor("registered", "Registered", "connectivity", false);
            return;
        }
        // The kiosk calls back to us (Answer/Decline) on the loopback address we advertise.
        try {
            callbacks = new CallbackServer(text(s, "intercomKey"), java.net.InetAddress.getByName("127.0.0.1"), 0, h::log);
        } catch (java.io.IOException failure) {
            String detail = "Cannot open the call callback port: " + failure.getMessage();
            h.status(detail, true);
            h.publishTextSensor("status", "Status", detail);
            return;
        }
        IntercomClient.Peer self = new IntercomClient.Peer(peerId(extension), "Kiosk Phone", "127.0.0.1", callbacks.port(), VERSION);
        IntercomClient intercom = new IntercomClient("127.0.0.1", number(s, "intercomPort"), text(s, "intercomKey"), self, h::log);
        intercom.setCallbackServer(callbacks);
        PageBridge bridge = new PageBridge(intercom, new PageBridge.Events() {
            @Override public void onRegistration(boolean registered, String detail) {
                PluginHost current = host;
                if (current == null) return;
                current.publishBinarySensor("registered", "Registered", "connectivity", registered);
                current.publishTextSensor("status", "Status", (registered ? "registered: " : "not registered: ") + detail);
                current.status(registered ? "Registered" : "Not registered: " + detail, !registered);
            }
            @Override public void onPage(String caller, String status, int sipCode, String detail) {
                PluginHost current = host;
                if (current == null) return;
                current.publishTextSensor("last_page", "Last page", caller + ": " + status + " (SIP " + sipCode + ")");
                current.log("page from " + caller + ": kiosk " + status + " → SIP " + sipCode + " (" + detail + ")");
            }
            @Override public void onFirstAudio(String caller, long kioskAnswerMs, long firstAudioMs) {
                PluginHost current = host;
                if (current == null) return;
                current.publishTextSensor("page_latency", "Page latency", "kiosk answer " + kioskAnswerMs + " ms, first audio " + firstAudioMs + " ms");
            }
            @Override public void onCall(String caller, String state, String detail) {
                PluginHost current = host;
                if (current == null) return;
                boolean idle = "declined".equals(state) || "cancelled".equals(state) || "ended".equals(state);
                current.publishTextSensor("call_state", "Call state", idle ? "idle" : state + ": " + caller);
                current.log("call from " + caller + ": " + state + " (" + detail + ")");
            }
            @Override public void onPageEnded(String caller, String reason) {
                PluginHost current = host;
                if (current != null) current.log("page from " + caller + " ended: " + reason);
            }
            @Override public void log(String message) {
                PluginHost current = host;
                if (current != null) current.log(message);
            }
        });
        try {
            bridge.setMicGain(text(s, "micGain").isEmpty() ? 8.0 : Double.parseDouble(text(s, "micGain")));
        } catch (NumberFormatException bad) {
            h.log("micGain is not a number; using 8");
            bridge.setMicGain(8.0);
        }
        sip = new SipClient(config, bridge);
        bridge.attach(sip);
        sip.start();
        h.status("Starting", false);
    }

    private void close() {
        SipClient current = sip;
        sip = null;
        if (current != null) current.stop();
        CallbackServer server = callbacks;
        callbacks = null;
        if (server != null) {
            try { server.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private static String text(Map<String, Object> s, String key) {
        Object value = s.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static int number(Map<String, Object> s, String key) {
        try {
            return Integer.parseInt(text(s, key));
        } catch (NumberFormatException error) {
            return 0; // SipClient.Config.problem() reports out-of-range ports
        }
    }

    /** Stable 16 hex chars per extension; the kiosk uses it to tell peers apart. */
    private static String peerId(String seed) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("kiosk-voip:" + seed).getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) id.append(String.format("%02x", hash[i] & 0xff));
            return id.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
