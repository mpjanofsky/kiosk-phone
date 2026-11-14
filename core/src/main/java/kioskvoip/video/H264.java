// SPDX-License-Identifier: Apache-2.0
package kioskvoip.video;

import java.io.ByteArrayOutputStream;

/**
 * The little H.264 knowledge the doorbell video needs: NAL unit types, Annex B assembly, and the picture size from a
 * sequence parameter set. No decoding.
 */
public final class H264 {
    public static final int NAL_IDR = 5, NAL_SEI = 6, NAL_SPS = 7, NAL_PPS = 8, NAL_AUD = 9;
    static final byte[] START = { 0, 0, 0, 1 };

    private H264() { }

    public static int type(byte[] nal) { return nal.length == 0 ? -1 : nal[0] & 0x1f; }

    /** Picture size coded in an SPS NAL unit (with its one-byte header), after cropping; null if it cannot be read. */
    public static int[] size(byte[] sps) {
        try {
            Bits b = new Bits(unescape(sps, 1));
            int profile = b.u(8);
            b.u(16); // constraint flags + level
            b.ue(); // seq_parameter_set_id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139
                || profile == 134 || profile == 135) {
                chroma = b.ue();
                if (chroma == 3) b.u(1);
                b.ue(); b.ue(); // bit depths
                b.u(1);
                if (b.u(1) == 1) { // seq_scaling_matrix_present
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (b.u(1) == 1) skipScaling(b, i < 6 ? 16 : 64);
                    }
                }
            }
            b.ue(); // log2_max_frame_num
            int poc = b.ue();
            if (poc == 0) b.ue();
            else if (poc == 1) {
                b.u(1); b.se(); b.se();
                int n = b.ue();
                for (int i = 0; i < n; i++) b.se();
            }
            b.ue(); // max_num_ref_frames
            b.u(1);
            int widthMbs = b.ue() + 1, heightUnits = b.ue() + 1;
            int frameMbsOnly = b.u(1);
            if (frameMbsOnly == 0) b.u(1);
            b.u(1); // direct_8x8_inference
            int width = widthMbs * 16, height = heightUnits * 16 * (2 - frameMbsOnly);
            if (b.u(1) == 1) { // frame_cropping
                int l = b.ue(), r = b.ue(), t = b.ue(), bo = b.ue();
                int cropX = chroma == 0 || chroma == 3 ? 1 : 2;
                int cropY = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
                width -= (l + r) * cropX;
                height -= (t + bo) * cropY;
            }
            return width > 0 && height > 0 && width < 65536 && height < 65536 ? new int[] { width, height } : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static void skipScaling(Bits b, int count) {
        int last = 8, next = 8;
        for (int j = 0; j < count; j++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Removes emulation prevention bytes (00 00 03 → 00 00) from {@code from} on. */
    static byte[] unescape(byte[] nal, int from) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = from; i < nal.length; i++) {
            int v = nal[i] & 0xff;
            if (zeros >= 2 && v == 3) { zeros = 0; continue; }
            out.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /** One access unit being put together from NAL units, written out in Annex B form. */
    static final class AccessUnit {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream(64 * 1024);
        boolean idr, sps, pps;
        int count;

        void add(byte[] nal) {
            if (nal.length == 0) return;
            int t = type(nal);
            if (t == NAL_AUD) return; // the kiosk does not need delimiters
            if (t == NAL_IDR) idr = true;
            if (t == NAL_SPS) sps = true;
            if (t == NAL_PPS) pps = true;
            data.write(START, 0, 4);
            data.write(nal, 0, nal.length);
            count++;
        }

        boolean isEmpty() { return count == 0; }

        /** Annex B bytes; a key frame without in-band SPS/PPS gets the ones given (from the SDP) in front. */
        byte[] annexB(byte[] spsNal, byte[] ppsNal) {
            byte[] body = data.toByteArray();
            if (!idr || (sps && pps) || spsNal == null || ppsNal == null) return body;
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + spsNal.length + ppsNal.length + 8);
            out.write(START, 0, 4);
            out.write(spsNal, 0, spsNal.length);
            out.write(START, 0, 4);
            out.write(ppsNal, 0, ppsNal.length);
            out.write(body, 0, body.length);
            return out.toByteArray();
        }

        void reset() {
            data.reset();
            idr = sps = pps = false;
            count = 0;
        }
    }

    /** Exp-Golomb bit reader. */
    static final class Bits {
        private final byte[] d;
        private int pos;

        Bits(byte[] d) { this.d = d; }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= d.length * 8) throw new IllegalStateException("SPS too short");
                v = (v << 1) | ((d[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) if (++zeros > 31) throw new IllegalStateException("bad exp-golomb");
            return (int) ((1L << zeros) - 1 + (zeros == 0 ? 0 : u(zeros)));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }
}
