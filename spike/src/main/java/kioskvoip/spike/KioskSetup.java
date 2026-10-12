// SPDX-License-Identifier: Apache-2.0
package kioskvoip.spike;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import kioskvoip.intercom.IntercomClient;

/**
 * Shared intercom settings for the spike tools. Env: KS_HOST, KS_INTERCOM_KEY (never printed), optional
 * KS_ADMIN_PORT (2324), KS_PEER_NAME_SEED (seed for our 16-hex peer id), KS_REPLY_PORT (where Reply would call back;
 * nothing listens yet).
 */
final class KioskSetup {
    static final String VERSION = "0.1.0";

    private KioskSetup() { }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isEmpty()) return value;
        if (fallback == null) throw new IllegalArgumentException("Set " + name);
        return fallback;
    }

    static void event(String text) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + text);
    }

    static IntercomClient client(String seed) throws Exception {
        String host = env("KS_HOST", null);
        String key = System.getenv("KS_INTERCOM_KEY");
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Set KS_INTERCOM_KEY (on the Mac: from the keychain item ks-intercom)");
        }
        String local;
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(host), 9); // picks the route; sends nothing
            local = probe.getLocalAddress().getHostAddress();
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(("kiosk-voip:" + seed).getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 8; i++) id.append(String.format("%02x", hash[i] & 0xff));
        IntercomClient.Peer self = new IntercomClient.Peer(id.toString(), "KS VoIP", local,
            Integer.parseInt(env("KS_REPLY_PORT", "39999")), VERSION);
        return new IntercomClient(host, Integer.parseInt(env("KS_ADMIN_PORT", "2324")), key, self, KioskSetup::event);
    }
}
