// SPDX-License-Identifier: Apache-2.0
package kioskvoip.sip;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SDP offer/answer for a single audio stream (RFC 3264, static payload types only). We answer sendrecv and send
 * silence: a recvonly answer let Asterisk's rtp_timeout end pages after 30 s.
 */
public final class Sdp {
    public static final int PCMU = 0, PCMA = 8, G722 = 9;

    private Sdp() {}

    public static final class Offer {
        public final InetAddress address;
        public final int port, payload;

        Offer(InetAddress address, int port, int payload) {
            this.address = address;
            this.port = port;
            this.payload = payload;
        }
    }

    public static String codecName(int payload) {
        switch (payload) {
            case PCMU: return "PCMU";
            case PCMA: return "PCMA";
            case G722: return "G722";
            default: return "PT" + payload;
        }
    }

    /**
     * Picks the first payload type from {@code preference} that the offer's first audio stream lists.
     * Media-level c= overrides session-level c=.
     */
    public static Offer parseOffer(String sdp, int[] preference) throws UnknownHostException {
        if (sdp == null || sdp.trim().isEmpty()) throw new IllegalArgumentException("INVITE has no SDP offer");
        String sessionIp = null, mediaIp = null;
        int port = -1;
        String[] formats = null;
        boolean inAudio = false;
        for (String raw : sdp.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                if (formats != null) break; // only the first audio stream
                inAudio = line.startsWith("m=audio ");
                if (inAudio) {
                    String[] parts = line.substring(8).trim().split(" +");
                    port = Integer.parseInt(parts[0].split("/")[0]);
                    formats = new String[Math.max(0, parts.length - 2)];
                    System.arraycopy(parts, 2, formats, 0, formats.length);
                }
            } else if (line.startsWith("c=IN IP4 ")) {
                String ip = line.substring(9).trim().split("/")[0];
                if (inAudio) mediaIp = ip;
                else if (formats == null) sessionIp = ip;
            }
        }
        if (formats == null || port < 1 || port > 65535) throw new IllegalArgumentException("SDP has no audio stream");
        String ip = mediaIp != null ? mediaIp : sessionIp;
        if (ip == null) throw new IllegalArgumentException("SDP has no IPv4 media address");
        for (int wanted : preference) {
            for (String format : formats) {
                if (format.equals(Integer.toString(wanted))) return new Offer(InetAddress.getByName(ip), port, wanted);
            }
        }
        throw new IllegalArgumentException("No common codec in offer " + String.join(" ", formats));
    }

    public static String answer(InetAddress local, int port, int payload, long sessionId) {
        String ip = local.getHostAddress();
        return "v=0\r\n"
            + "o=- " + sessionId + " 1 IN IP4 " + ip + "\r\n"
            + "s=kiosk-voip\r\n"
            + "c=IN IP4 " + ip + "\r\n"
            + "t=0 0\r\n"
            + "m=audio " + port + " RTP/AVP " + payload + "\r\n"
            + "a=rtpmap:" + payload + " " + codecName(payload) + "/8000\r\n"
            + "a=ptime:20\r\n"
            + "a=sendrecv\r\n";
    }
}
