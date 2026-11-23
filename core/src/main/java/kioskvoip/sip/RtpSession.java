// SPDX-License-Identifier: Apache-2.0
package kioskvoip.sip;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * One RTP audio stream (G.722, PCMU or PCMA): incoming audio goes to the sink as 16 kHz mono PCM16 (the KS intercom
 * format); outgoing is 20 ms packets from the same port: microphone audio queued with {@link #writeMic} when there is
 * some, silence otherwise, so PBX RTP timeouts (Asterisk rtp_timeout) don't drop long pages.
 */
public final class RtpSession implements Closeable {
    public interface Sink {
        /** Called on the receiver thread for every packet. */
        void audio(short[] pcm16k);
    }

    private final DatagramSocket socket;
    private final InetAddress peer;
    private final InetSocketAddress remote;
    private final int payload;
    /** telephone-event payload type agreed in SDP, or -1: then DTMF cannot go in-band. */
    private final int eventPayload;
    /** DTMF digits waiting to be sent as RFC 4733 events. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Character> dtmf = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Sink sink;
    private volatile boolean running;
    private Thread thread, sender;
    private short previous;
    private final G722.Decoder g722 = new G722.Decoder();
    private volatile long packets, dropped;
    /** Mic audio waiting to be sent (16 kHz PCM); capped so a stalled sender cannot grow it. */
    private final short[] mic = new short[MIC_CAPACITY];
    private int micStart, micCount;
    private volatile long micPackets;
    static final int PACKET_SAMPLES = 320, MIC_CAPACITY = 16_000; // 20 ms per packet; 1 s of mic

    public RtpSession(InetAddress local, InetSocketAddress remote, int payload, Sink sink) throws SocketException {
        this(local, remote, payload, -1, sink);
    }

    public RtpSession(InetAddress local, InetSocketAddress remote, int payload, int eventPayload, Sink sink) throws SocketException {
        if (payload != Sdp.PCMU && payload != Sdp.PCMA && payload != Sdp.G722) {
            throw new IllegalArgumentException("Unsupported payload " + payload);
        }
        this.remote = remote;
        this.peer = remote.getAddress();
        this.payload = payload;
        this.eventPayload = eventPayload;
        this.sink = sink;
        socket = new DatagramSocket(new InetSocketAddress(local, 0));
        socket.setSoTimeout(200);
    }

    public int port() { return socket.getLocalPort(); }

    /** True when the far end agreed to telephone-event, so {@link #sendDtmf} can work. */
    public boolean canSendDtmf() { return eventPayload >= 0; }

    /** Queues DTMF digits (0-9, *, #, A-D) to go out as RFC 4733 events in this stream. False when not agreed. */
    public boolean sendDtmf(String digits) {
        if (eventPayload < 0) return false;
        for (char c : digits.toCharArray()) {
            if (eventCode(c) < 0) throw new IllegalArgumentException("not a DTMF digit: " + c);
        }
        for (char c : digits.toCharArray()) dtmf.add(c);
        return true;
    }

    static int eventCode(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (Character.toUpperCase(c)) {
            case '*': return 10;
            case '#': return 11;
            case 'A': return 12;
            case 'B': return 13;
            case 'C': return 14;
            case 'D': return 15;
            default: return -1;
        }
    }

    /** Packets per digit: 8 of 20 ms (160 ms tone), then 3 end packets; then 4 packets of audio as a gap. */
    static final int DTMF_TONE_PACKETS = 8, DTMF_END_PACKETS = 3, DTMF_GAP_PACKETS = 4;
    public long packets() { return packets; }
    /** Packets ignored: wrong source, payload type or malformed. */
    public long dropped() { return dropped; }
    /** Outgoing packets that carried mic audio rather than silence. */
    public long micPackets() { return micPackets; }

    /** Queues 16 kHz mono PCM to send to the far end; the oldest audio is discarded beyond one second. Any thread. */
    public void writeMic(short[] pcm16k) {
        synchronized (mic) {
            int offset = 0, n = pcm16k.length;
            if (n > MIC_CAPACITY) { offset = n - MIC_CAPACITY; n = MIC_CAPACITY; }
            int overflow = micCount + n - MIC_CAPACITY;
            if (overflow > 0) { micStart = (micStart + overflow) % MIC_CAPACITY; micCount -= overflow; }
            for (int i = 0; i < n; i++) mic[(micStart + micCount + i) % MIC_CAPACITY] = pcm16k[offset + i];
            micCount += n;
        }
    }

    /** Takes one packet of mic audio into {@code out}; false (out untouched) when less than a full packet is queued. */
    private boolean takeMic(short[] out) {
        synchronized (mic) {
            if (micCount < out.length) return false;
            for (int i = 0; i < out.length; i++) out[i] = mic[(micStart + i) % MIC_CAPACITY];
            micStart = (micStart + out.length) % MIC_CAPACITY;
            micCount -= out.length;
            return true;
        }
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "rtp-rx");
        thread.setDaemon(true);
        thread.start();
        sender = new Thread(this::sendLoop, "rtp-tx");
        sender.setDaemon(true);
        sender.start();
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        for (Thread worker : new Thread[] {thread, sender}) {
            if (worker == null || worker == Thread.currentThread()) continue;
            worker.interrupt();
            try { worker.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void sendLoop() {
        java.util.Random random = new java.util.Random();
        int sequence = random.nextInt(65536), timestamp = random.nextInt(), ssrc = random.nextInt();
        byte[] packet = new byte[12 + 160];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) payload;
        packet[8] = (byte) (ssrc >>> 24); packet[9] = (byte) (ssrc >>> 16); packet[10] = (byte) (ssrc >>> 8); packet[11] = (byte) ssrc;
        G722.Encoder encoder = payload == Sdp.G722 ? new G722.Encoder() : null;
        short[] samples = new short[PACKET_SAMPLES];
        byte[] event = new byte[12 + 4];
        event[0] = (byte) 0x80;
        System.arraycopy(packet, 8, event, 8, 4);
        int eventCode = -1, eventStep = 0, eventStart = 0, gap = 0;
        long next = System.nanoTime();
        while (running) {
            if (eventCode < 0 && gap == 0 && eventPayload >= 0) {
                Character digit = dtmf.poll();
                if (digit != null) { eventCode = eventCode(digit); eventStep = 0; eventStart = timestamp; }
            }
            if (eventCode >= 0) {
                // RFC 4733: same timestamp for the whole event, marker on its first packet, duration grows, E bit at the end.
                boolean end = eventStep >= DTMF_TONE_PACKETS;
                int duration = Math.min(eventStep + 1, DTMF_TONE_PACKETS) * 160;
                event[1] = (byte) ((eventStep == 0 ? 0x80 : 0) | eventPayload);
                event[2] = (byte) (sequence >>> 8); event[3] = (byte) sequence;
                event[4] = (byte) (eventStart >>> 24); event[5] = (byte) (eventStart >>> 16); event[6] = (byte) (eventStart >>> 8); event[7] = (byte) eventStart;
                event[12] = (byte) eventCode;
                event[13] = (byte) ((end ? 0x80 : 0) | 10); // volume -10 dBm0
                event[14] = (byte) (duration >>> 8); event[15] = (byte) duration;
                try {
                    socket.send(new DatagramPacket(event, event.length, remote));
                } catch (IOException closed) {
                    return;
                }
                sequence = (sequence + 1) & 0xffff;
                eventStep++;
                if (eventStep >= DTMF_TONE_PACKETS + DTMF_END_PACKETS) {
                    eventCode = -1;
                    gap = DTMF_GAP_PACKETS;
                    timestamp = eventStart + (DTMF_TONE_PACKETS + DTMF_END_PACKETS) * 160; // keep the clock in step with real time
                }
                next += 20_000_000L;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    try { Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L)); } catch (InterruptedException e) { return; }
                }
                continue;
            }
            if (gap > 0) gap--;
            boolean live = takeMic(samples);
            if (!live) java.util.Arrays.fill(samples, (short) 0);
            else micPackets++;
            if (encoder != null) {
                System.arraycopy(encoder.encode(samples), 0, packet, 12, 160);
            } else {
                for (int i = 0; i < 160; i++) {
                    short x = (short) ((samples[2 * i] + samples[2 * i + 1]) / 2); // 16 → 8 kHz
                    packet[12 + i] = payload == Sdp.PCMU ? G711.linearToMu(x) : G711.linearToA(x);
                }
            }
            packet[2] = (byte) (sequence >>> 8); packet[3] = (byte) sequence;
            packet[4] = (byte) (timestamp >>> 24); packet[5] = (byte) (timestamp >>> 16); packet[6] = (byte) (timestamp >>> 8); packet[7] = (byte) timestamp;
            try {
                socket.send(new DatagramPacket(packet, packet.length, remote));
            } catch (IOException closed) {
                return;
            }
            sequence = (sequence + 1) & 0xffff;
            timestamp += 160;
            next += 20_000_000L;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                try { Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L)); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void run() {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException timeout) {
                continue;
            } catch (IOException closed) {
                return;
            }
            if (!p.getAddress().equals(peer)) { dropped++; continue; }
            short[] pcm = decode(buf, p.getLength());
            if (pcm == null) { dropped++; continue; }
            packets++;
            try { sink.audio(pcm); } catch (RuntimeException ignored) { }
        }
    }

    /** RTP packet → 16 kHz PCM, or null if it is not our audio. */
    short[] decode(byte[] buf, int length) {
        int start = payloadOffset(buf, length);
        if (start < 0 || (buf[1] & 0x7f) != payload) return null;
        int n = length - start;
        if ((buf[0] & 0x20) != 0) n -= buf[length - 1] & 0xff; // padding
        if (n <= 0) return null;
        if (payload == Sdp.G722) return g722.decode(buf, start, n);
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            byte b = buf[start + i];
            short x = payload == Sdp.PCMU ? G711.muToLinear(b) : G711.aToLinear(b);
            // G.711: 8 → 16 kHz by linear interpolation.
            out[2 * i] = (short) ((previous + x) / 2);
            out[2 * i + 1] = x;
            previous = x;
        }
        return out;
    }

    /** Offset of the payload, or -1 for a malformed packet. */
    static int payloadOffset(byte[] buf, int length) {
        if (length < 12 || (buf[0] & 0xc0) != 0x80) return -1;
        int offset = 12 + (buf[0] & 0x0f) * 4;
        if ((buf[0] & 0x10) != 0) {
            if (length < offset + 4) return -1;
            int words = ((buf[offset + 2] & 0xff) << 8) | (buf[offset + 3] & 0xff);
            offset += 4 + words * 4;
        }
        return offset < length ? offset : -1;
    }
}
