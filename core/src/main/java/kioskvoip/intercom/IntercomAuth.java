// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Intercom key digest and one-use call tokens (docs/protocol.md, "Auth"). Never logs or returns the key. */
public final class IntercomAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final long TOKEN_LIFETIME_MS = 60_000;

    private IntercomAuth() { }

    /** First 8 hex chars of sha256(key): what identity reports as {@code key}. */
    public static String digest(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return hex(hash).substring(0, 8);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** {@code payload.sig}: urlsafe base64 (padded) of the claims JSON, HMAC-SHA256 keyed with "intercom:" + key. */
    public static String token(String key, String call, String from) {
        return token(key, call, from, System.currentTimeMillis() + TOKEN_LIFETIME_MS, hex(randomBytes(12)));
    }

    static String token(String key, String call, String from, long expiresMs, String nonce) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("intercom", call);
        claims.put("from", from);
        claims.put("n", nonce);
        claims.put("exp", expiresMs);
        String payload = Base64.getUrlEncoder().encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().encodeToString(hmac(key, payload));
    }

    static byte[] hmac(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("intercom:" + key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static byte[] randomBytes(int count) {
        byte[] data = new byte[count];
        RANDOM.nextBytes(data);
        return data;
    }

    static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }
}
