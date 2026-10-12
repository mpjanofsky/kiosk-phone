// SPDX-License-Identifier: Apache-2.0
// Adapted from meiser79/kiosk-satellite-sip-phone @ d4cec06 (Apache-2.0); changed: package name.
package kioskvoip.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** SIP Digest authentication (RFC 2617, MD5 only). */
final class SipDigest {
    private SipDigest() {}

    static String md5(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** qop is null for the legacy form without client nonce. */
    static String response(String user, String realm, String password, String method, String uri,
                           String nonce, String qop, String nc, String cnonce) {
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        return qop == null
            ? md5(ha1 + ":" + nonce + ":" + ha2)
            : md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /** Parses key=value pairs of a Digest header. Quoted values may contain commas. */
    static Map<String, String> parseParams(String header) {
        String h = header.trim();
        if (h.regionMatches(true, 0, "Digest", 0, 6)) h = h.substring(6).trim();
        Map<String, String> out = new HashMap<>();
        int i = 0, n = h.length();
        while (i < n) {
            while (i < n && (h.charAt(i) == ',' || Character.isWhitespace(h.charAt(i)))) i++;
            int eq = h.indexOf('=', i);
            if (eq < 0) break;
            String key = h.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            i = eq + 1;
            String value;
            if (i < n && h.charAt(i) == '"') {
                int end = h.indexOf('"', i + 1);
                if (end < 0) end = n;
                value = h.substring(i + 1, end);
                i = Math.min(end + 1, n);
            } else {
                int end = h.indexOf(',', i);
                if (end < 0) end = n;
                value = h.substring(i, end).trim();
                i = end;
            }
            out.put(key, value);
        }
        return out;
    }
}
