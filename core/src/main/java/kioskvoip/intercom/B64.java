// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

/** Base64 without java.util.Base64, which Android only has from API 26 (the Echo Dots run older). */
final class B64 {
    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private B64() { }

    static String encode(byte[] data) { return encode(data, STD); }

    /** URL-safe alphabet, padded, like java.util.Base64.getUrlEncoder(). */
    static String encodeUrl(byte[] data) { return encode(data, URL); }

    private static String encode(byte[] d, String alpha) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xff) << 16;
            if (i + 1 < d.length) b |= (d[i + 1] & 0xff) << 8;
            if (i + 2 < d.length) b |= d[i + 2] & 0xff;
            sb.append(alpha.charAt(b >> 18 & 63)).append(alpha.charAt(b >> 12 & 63));
            sb.append(i + 1 < d.length ? alpha.charAt(b >> 6 & 63) : '=');
            sb.append(i + 2 < d.length ? alpha.charAt(b & 63) : '=');
        }
        return sb.toString();
    }

    /** Accepts the URL-safe or standard alphabet, with or without padding. Throws IllegalArgumentException on bad input. */
    static byte[] decodeUrl(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        byte[] out = new byte[end * 6 / 8];
        int acc = 0, bits = 0, n = 0;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            int v = c == '-' ? 62 : c == '_' ? 63 : STD.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Illegal base64 character");
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; out[n++] = (byte) (acc >> bits); acc &= (1 << bits) - 1; }
        }
        if (end % 4 == 1) throw new IllegalArgumentException("Illegal base64 length");
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }
}
