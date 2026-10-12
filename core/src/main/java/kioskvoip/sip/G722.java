// Java port of the SpanDSP G.722 codec as shipped in Asterisk (codecs/g722/g722_decode.c, g722_encode.c).
// Written by Steve Underwood, who placed his contributions in the public domain; based in part on a single channel
// G.722 codec, Copyright (c) CMU 1993 Computer Science, Speech Group, Chengxiang Lu and Alex Hauptmann.
// Changed: Java, 64 kbit/s unpacked mode only (the RTP G.722 format), no 8 kHz or ITU test modes.
package kioskvoip.sip;

/** ITU-T G.722 at 64 kbit/s: one byte per two 16 kHz samples. Each instance keeps one direction's state. */
public final class G722 {
    private G722() {}

    private static final int[] WL = {-60, -30, 58, 172, 334, 538, 1198, 3042};
    private static final int[] RL42 = {0, 7, 6, 5, 4, 3, 2, 1, 7, 6, 5, 4, 3, 2, 1, 0};
    private static final int[] ILB = {
        2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383, 2435, 2489, 2543, 2599, 2656, 2714,
        2774, 2834, 2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371, 3444, 3520, 3597, 3676,
        3756, 3838, 3922, 4008
    };
    private static final int[] WH = {0, -214, 798};
    private static final int[] RH2 = {2, 1, 2, 1};
    private static final int[] QM2 = {-7408, -1616, 7408, 1616};
    private static final int[] QM4 = {
        0, -20456, -12896, -8968, -6288, -4240, -2584, -1200,
        20456, 12896, 8968, 6288, 4240, 2584, 1200, 0
    };
    private static final int[] QM6 = {
        -136, -136, -136, -136, -24808, -21904, -19008, -16704,
        -14984, -13512, -12280, -11192, -10232, -9360, -8576, -7856,
        -7192, -6576, -6000, -5456, -4944, -4464, -4008, -3576,
        -3168, -2776, -2400, -2032, -1688, -1360, -1040, -728,
        24808, 21904, 19008, 16704, 14984, 13512, 12280, 11192,
        10232, 9360, 8576, 7856, 7192, 6576, 6000, 5456,
        4944, 4464, 4008, 3576, 3168, 2776, 2400, 2032,
        1688, 1360, 1040, 728, 432, 136, -432, -136
    };
    private static final int[] QMF = {3, -11, 12, 32, -210, 951, 3876, -805, 362, -156, 53, -11};
    // Encoder only.
    private static final int[] Q6 = {
        0, 35, 72, 110, 150, 190, 233, 276, 323, 370, 422, 473, 530, 587, 650, 714,
        786, 858, 940, 1023, 1121, 1219, 1339, 1458, 1612, 1765, 1980, 2195, 2557, 2919, 0, 0
    };
    private static final int[] ILN = {
        0, 63, 62, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19,
        18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 0
    };
    private static final int[] ILP = {
        0, 61, 60, 59, 58, 57, 56, 55, 54, 53, 52, 51, 50, 49, 48, 47,
        46, 45, 44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 0
    };
    private static final int[] IHN = {0, 1, 0};
    private static final int[] IHP = {0, 3, 2};

    private static int saturate(int amp) {
        return amp > Short.MAX_VALUE ? Short.MAX_VALUE : amp < Short.MIN_VALUE ? Short.MIN_VALUE : amp;
    }

    /** Adaptive predictor state of one sub-band. */
    private static final class Band {
        int s, sp, sz, nb, det;
        final int[] r = new int[3], a = new int[3], ap = new int[3], p = new int[3];
        final int[] d = new int[7], b = new int[7], bp = new int[7], sg = new int[7];

        Band(int det) { this.det = det; }

        /** Block 4: reconstruction and predictor adaptation. */
        void block4(int dd) {
            int wd1, wd2, wd3;
            d[0] = dd;
            r[0] = saturate(s + dd);
            p[0] = saturate(sz + dd);

            for (int i = 0; i < 3; i++) sg[i] = p[i] >> 15;
            wd1 = saturate(a[1] << 2);
            wd2 = (sg[0] == sg[1]) ? -wd1 : wd1;
            if (wd2 > 32767) wd2 = 32767;
            wd3 = (sg[0] == sg[2]) ? 128 : -128;
            wd3 += wd2 >> 7;
            wd3 += (a[2] * 32512) >> 15;
            if (wd3 > 12288) wd3 = 12288;
            else if (wd3 < -12288) wd3 = -12288;
            ap[2] = wd3;

            sg[0] = p[0] >> 15;
            sg[1] = p[1] >> 15;
            wd1 = (sg[0] == sg[1]) ? 192 : -192;
            wd2 = (a[1] * 32640) >> 15;
            ap[1] = saturate(wd1 + wd2);
            wd3 = saturate(15360 - ap[2]);
            if (ap[1] > wd3) ap[1] = wd3;
            else if (ap[1] < -wd3) ap[1] = -wd3;

            wd1 = (dd == 0) ? 0 : 128;
            sg[0] = dd >> 15;
            for (int i = 1; i < 7; i++) {
                sg[i] = d[i] >> 15;
                wd2 = (sg[i] == sg[0]) ? wd1 : -wd1;
                wd3 = (b[i] * 32640) >> 15;
                bp[i] = saturate(wd2 + wd3);
            }

            for (int i = 6; i > 0; i--) {
                d[i] = d[i - 1];
                b[i] = bp[i];
            }
            for (int i = 2; i > 0; i--) {
                r[i] = r[i - 1];
                p[i] = p[i - 1];
                a[i] = ap[i];
            }

            wd1 = saturate(r[1] + r[1]);
            wd1 = (a[1] * wd1) >> 15;
            wd2 = saturate(r[2] + r[2]);
            wd2 = (a[2] * wd2) >> 15;
            sp = saturate(wd1 + wd2);

            sz = 0;
            for (int i = 6; i > 0; i--) {
                wd1 = saturate(d[i] + d[i]);
                sz += (b[i] * wd1) >> 15;
            }
            sz = saturate(sz);

            s = saturate(sp + sz);
        }

        /** Blocks 3L/3H: log scale factor update and scale. */
        void scale(int wd, int max, int shift) {
            int nbNew = ((nb * 127) >> 7) + wd;
            nb = nbNew < 0 ? 0 : Math.min(nbNew, max);
            int wd1 = (nb >> 6) & 31;
            int wd2 = shift - (nb >> 11);
            int wd3 = (wd2 < 0) ? (ILB[wd1] << -wd2) : (ILB[wd1] >> wd2);
            det = wd3 << 2;
        }
    }

    public static final class Decoder {
        private final Band low = new Band(32), high = new Band(8);
        private final int[] x = new int[24];

        /** Decodes {@code len} bytes into 2*len samples. */
        public short[] decode(byte[] data, int offset, int len) {
            short[] out = new short[len * 2];
            int n = 0;
            for (int j = offset; j < offset + len; j++) {
                int code = data[j] & 0xff;
                int wd1 = code & 0x3f;
                int ihigh = (code >> 6) & 0x03;
                int wd2 = QM6[wd1];
                wd1 >>= 2;

                wd2 = (low.det * wd2) >> 15;
                int rlow = low.s + wd2;
                if (rlow > 16383) rlow = 16383;
                else if (rlow < -16384) rlow = -16384;

                int dlowt = (low.det * QM4[wd1]) >> 15;
                low.scale(WL[RL42[wd1]], 18432, 8);
                low.block4(dlowt);

                int dhigh = (high.det * QM2[ihigh]) >> 15;
                int rhigh = dhigh + high.s;
                if (rhigh > 16383) rhigh = 16383;
                else if (rhigh < -16384) rhigh = -16384;
                high.scale(WH[RH2[ihigh]], 22528, 10);
                high.block4(dhigh);

                // Receive QMF.
                System.arraycopy(x, 2, x, 0, 22);
                x[22] = rlow + rhigh;
                x[23] = rlow - rhigh;
                int xout1 = 0, xout2 = 0;
                for (int i = 0; i < 12; i++) {
                    xout2 += x[2 * i] * QMF[i];
                    xout1 += x[2 * i + 1] * QMF[11 - i];
                }
                out[n++] = (short) (xout1 >> 11);
                out[n++] = (short) (xout2 >> 11);
            }
            return out;
        }
    }

    public static final class Encoder {
        private final Band low = new Band(32), high = new Band(8);
        private final int[] x = new int[24];

        /** Encodes an even number of 16 kHz samples into length/2 bytes. */
        public byte[] encode(short[] amp) {
            byte[] out = new byte[amp.length / 2];
            int n = 0;
            for (int j = 0; j + 1 < amp.length; ) {
                // Transmit QMF.
                System.arraycopy(x, 2, x, 0, 22);
                x[22] = amp[j++];
                x[23] = amp[j++];
                int sumeven = 0, sumodd = 0;
                for (int i = 0; i < 12; i++) {
                    sumodd += x[2 * i] * QMF[i];
                    sumeven += x[2 * i + 1] * QMF[11 - i];
                }
                int xlow = (sumeven + sumodd) >> 14;
                int xhigh = (sumeven - sumodd) >> 14;

                int el = saturate(xlow - low.s);
                int wd = (el >= 0) ? el : -(el + 1);
                int i;
                for (i = 1; i < 30; i++) {
                    if (wd < ((Q6[i] * low.det) >> 12)) break;
                }
                int ilow = (el < 0) ? ILN[i] : ILP[i];
                int ril = ilow >> 2;
                int dlow = (low.det * QM4[ril]) >> 15;
                low.scale(WL[RL42[ril]], 18432, 8);
                low.block4(dlow);

                int eh = saturate(xhigh - high.s);
                wd = (eh >= 0) ? eh : -(eh + 1);
                int mih = (wd >= ((564 * high.det) >> 12)) ? 2 : 1;
                int ihigh = (eh < 0) ? IHN[mih] : IHP[mih];
                int dhigh = (high.det * QM2[ihigh]) >> 15;
                high.scale(WH[RH2[ihigh]], 22528, 10);
                high.block4(dhigh);

                out[n++] = (byte) ((ihigh << 6) | ilow);
            }
            return out;
        }
    }
}
