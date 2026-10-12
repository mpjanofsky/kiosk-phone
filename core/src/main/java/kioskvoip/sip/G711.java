// SPDX-License-Identifier: Apache-2.0
// Codec math adapted from meiser79/kiosk-satellite-sip-phone @ d4cec06 (Apache-2.0, SipAudio.java); changed: own class, A-law encoder segment fix for 256..511.
package kioskvoip.sip;

/** G.711 mu-law (PCMU) and A-law (PCMA) sample conversion. */
public final class G711 {
    private G711() {}

    public static byte linearToMu(short sample) {
        int x = sample;
        int sign = (x >> 8) & 0x80;
        if (sign != 0) x = -x;
        if (x > 32635) x = 32635;
        x += 0x84;
        int exponent = 7;
        for (int mask = 0x4000; (x & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (x >> (exponent + 3)) & 0x0f;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    public static short muToLinear(byte value) {
        int u = (~value) & 255;
        int t = ((u & 15) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (short) (((u & 0x80) != 0) ? (0x84 - t) : (t - 0x84));
    }

    public static byte linearToA(short sample) {
        int x = sample;
        int mask;
        if (x >= 0) mask = 0xd5;
        else { mask = 0x55; x = -x - 1; }
        if (x > 32767) x = 32767;
        int seg = 0;
        if (x >= 256) { int v = x >> 8; while (v > 1 && seg < 7) { v >>= 1; seg++; } seg++; }
        int aval = seg << 4;
        if (seg < 2) aval |= (x >> 4) & 15;
        else aval |= (x >> (seg + 3)) & 15;
        return (byte) (aval ^ mask);
    }

    public static short aToLinear(byte value) {
        int a = (value & 255) ^ 0x55;
        int t = (a & 15) << 4;
        int seg = (a & 0x70) >> 4;
        if (seg == 0) t += 8;
        else if (seg == 1) t += 0x108;
        else { t += 0x108; t <<= seg - 1; }
        return (short) (((a & 0x80) != 0) ? t : -t);
    }
}
