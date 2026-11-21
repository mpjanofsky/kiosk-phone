// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kioskvoip.video.RtspSource;

/**
 * Credentials from a button URL's user info ({@code http://user:password@host/...}). HttpURLConnection ignores them,
 * so the URL is sent without them and a 401 is answered with Basic or Digest (MD5/MD5-sess, qop=auth) auth.
 * Neither the password nor the full URL ever goes into a log line.
 */
final class HttpAuth {
    /** The URL without user info. */
    final String url;
    /** Path and query as sent in the request line (Digest {@code uri=}). */
    final String uri;
    final String user, password;

    private HttpAuth(String url, String uri, String user, String password) {
        this.url = url; this.uri = uri; this.user = user; this.password = password;
    }

    static HttpAuth of(String target) {
        URI u;
        try {
            u = new URI(target);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("not a valid URL");
        }
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String uri = u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        String info = u.getRawUserInfo();
        if (info == null) return new HttpAuth(target, uri, null, null);
        int colon = info.indexOf(':');
        String user = decode(colon < 0 ? info : info.substring(0, colon));
        String password = colon < 0 ? "" : decode(info.substring(colon + 1));
        String port = u.getPort() < 0 ? "" : ":" + u.getPort();
        String clean = u.getScheme() + "://" + u.getHost() + port + uri + (u.getRawFragment() == null ? "" : "#" + u.getRawFragment());
        return new HttpAuth(clean, uri, user, password);
    }

    boolean hasCredentials() { return user != null; }

    /** The Authorization value answering one of the server's challenges (Digest preferred), or null. */
    String answer(List<String> challenges, String method) {
        if (user == null || challenges == null) return null;
        String basic = null;
        for (String c : challenges) {
            if (c == null) continue;
            String h = c.trim();
            if (h.regionMatches(true, 0, "Digest", 0, 6)) {
                String digest = digest(RtspSource.params(h.substring(6)), method);
                if (digest != null) return digest;
            } else if (h.regionMatches(true, 0, "Basic", 0, 5)) {
                basic = "Basic " + RtspSource.base64((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            }
        }
        return basic;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    String digest(Map<String, String> p, String method) {
        String realm = p.get("realm"), nonce = p.get("nonce");
        if (realm == null || nonce == null) return null;
        String algorithm = p.getOrDefault("algorithm", "MD5");
        boolean sess = algorithm.equalsIgnoreCase("MD5-sess");
        if (!sess && !algorithm.equalsIgnoreCase("MD5")) return null;
        boolean qop = false;
        if (p.containsKey("qop")) {
            for (String q : p.get("qop").split(",")) if (q.trim().equalsIgnoreCase("auth")) qop = true;
            if (!qop) return null; // only auth-int offered
        }
        String cnonce = Long.toHexString(RANDOM.nextLong()), nc = "00000001";
        String ha1 = md5(user + ":" + realm + ":" + password);
        if (sess) ha1 = md5(ha1 + ":" + nonce + ":" + cnonce);
        String ha2 = md5(method + ":" + uri);
        String response = qop ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2) : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", response=\"").append(response).append('"');
        if (p.containsKey("algorithm")) h.append(", algorithm=").append(algorithm);
        if (qop) h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        if (p.containsKey("opaque")) h.append(", opaque=\"").append(p.get("opaque")).append('"');
        return h.toString();
    }

    static String md5(String value) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : h) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return s.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return s;
        }
    }
}
