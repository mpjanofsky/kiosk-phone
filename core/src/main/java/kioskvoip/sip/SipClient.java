// SPDX-License-Identifier: Apache-2.0
// Adapted from meiser79/kiosk-satellite-sip-phone @ d4cec06 (Apache-2.0); changed: package, auto-answer with
// accept/reject decided by the listener (486/480/488), RTP via RtpSession (receive, send silence) instead of Android audio,
// 200 OK retransmission until ACK, re-INVITE handling, BYE to the remote Contact, English messages.
package kioskvoip.sip;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/**
 * SIP user agent over UDP for paging: registers with a PBX and auto-answers INVITEs; page audio flows in, silence flows out.
 * All SIP work runs on one owned thread; listener callbacks other than log() never run with the internal lock held.
 */
public final class SipClient {
    public interface Listener {
        void onRegistration(boolean registered, String detail);
        /** A new INVITE with a usable offer. Called on the SIP thread; must return quickly. */
        Answer onInvite(String caller, String codec);
        /** Same, with the INVITE's Alert-Info and Call-Info headers (null when absent), to tell a page from a call. */
        default Answer onInvite(String caller, String codec, String alertInfo, String callInfo) { return onInvite(caller, codec); }
        /** The call is answered and media flows; {@code dropped} RTP stats come with onCallEnded. */
        default void onCallStarted(String caller) { }
        void onCallEnded(String reason, long packets, long dropped);
        void log(String message);
    }

    /** Told when a ringing call ends before the listener answered it. Called off the SIP lock. */
    public interface RingHandler {
        /** The caller hung up (CANCEL) or we gave up waiting ({@code reason}); the INVITE is already answered with 487/480. */
        void onCancelled(String reason);
    }

    /** What to do with an INVITE: answer into a sink, ring until decided, or reject with a SIP status. */
    public static final class Answer {
        final RtpSession.Sink sink;
        final RingHandler ring;
        final int code;
        final String reason;

        private Answer(RtpSession.Sink sink, RingHandler ring, int code, String reason) {
            this.sink = sink;
            this.ring = ring;
            this.code = code;
            this.reason = reason;
        }

        public static Answer accept(RtpSession.Sink sink) { return new Answer(sink, null, 200, "OK"); }
        /** Send 180 Ringing now; finish later with {@link SipClient#acceptRinging} or {@link SipClient#declineRinging}. */
        public static Answer ring(RingHandler handler) { return new Answer(null, handler, 180, "Ringing"); }
        public static Answer busy() { return new Answer(null, null, 486, "Busy Here"); }
        public static Answer unavailable() { return new Answer(null, null, 480, "Temporarily Unavailable"); }
    }

    public static final class Config {
        public final String server, user, authUser, password;
        public final int port, localPort, expires;

        public Config(String server, int port, String user, String authUser, String password, int localPort, int expires) {
            this.server = server;
            this.port = port;
            this.user = user;
            this.authUser = authUser;
            this.password = password;
            this.localPort = localPort;
            this.expires = expires;
        }

        /** A human readable problem, or null when the configuration can be used. */
        public String problem() {
            if (server.isEmpty()) return "No server configured";
            if (user.isEmpty()) return "No user configured";
            if (port < 1 || port > 65535) return "Server port must be between 1 and 65535";
            if (localPort < 1024 || localPort > 65535) return "Local port must be between 1024 and 65535";
            if (expires < 60 || expires > 3600) return "Registration expiry must be between 60 and 3600 seconds";
            for (String value : new String[] {server, user, authUser, password}) {
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return "Settings must not contain line breaks";
            }
            // The password only enters the digest hash, the other values are written into headers.
            for (String value : new String[] {server, user, authUser}) {
                if (value.indexOf('"') >= 0 || value.indexOf(' ') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    return "Server and user values must not contain spaces, quotes or angle brackets";
                }
            }
            return null;
        }
    }

    /** Codec preference: G.722 is 16 kHz like the KS intercom; G.711 is the fallback. */
    static final int[] CODECS = {Sdp.G722, Sdp.PCMU, Sdp.PCMA};

    private static final long RETRY_MS = 30_000;
    private static final int NETWORK_RETRY_MS = 5_000;
    private static final long T1_MS = 500, T2_MS = 4_000, ANSWER_TIMEOUT_MS = 32_000;
    /** Under KS's 50 s answer window, so we give up first and the caller hears 480. */
    static final long RING_TIMEOUT_MS = 45_000;
    private static final String ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS";
    private static final String USER_AGENT = "kiosk-voip/0.1";

    /** The one active call. */
    private static final class Call {
        final SipMessage invite;
        final String toTag;
        final InetSocketAddress source;
        final byte[] ok;
        final RtpSession rtp;
        final String caller;
        boolean acked;
        long okSentAt, nextOkAt, okInterval = T1_MS;

        Call(SipMessage invite, String toTag, InetSocketAddress source, byte[] ok, RtpSession rtp, String caller) {
            this.invite = invite; this.toTag = toTag; this.source = source; this.ok = ok; this.rtp = rtp; this.caller = caller;
        }
    }

    /** An INVITE we have sent 180 for and not yet answered. */
    private static final class Ringing {
        final SipMessage invite;
        final InetSocketAddress source;
        final Sdp.Offer offer;
        final String toTag, caller;
        final RingHandler handler;
        final long startedAt = System.currentTimeMillis();

        Ringing(SipMessage invite, InetSocketAddress source, Sdp.Offer offer, String toTag, String caller, RingHandler handler) {
            this.invite = invite; this.source = source; this.offer = offer; this.toTag = toTag; this.caller = caller; this.handler = handler;
        }
    }

    private final Config config;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final String registerCallId = hex(8) + "@kiosk-voip";
    private final String registerTag = hex(4);

    private volatile boolean running;
    private volatile DatagramSocket socket;
    private Thread thread;

    // Touched by the network thread only.
    private InetAddress serverAddress;
    private String localIp;
    private int cseq;
    private int lastRegisterCseq = -1;
    private boolean lastRegisterHadAuth;
    private long nextRegisterAt;

    // Guarded by lock.
    private boolean registered;
    private String lastDetail = "";
    private Call call;
    private Ringing ringing;
    private long ringTimeoutMs = RING_TIMEOUT_MS;
    private String lastFinalCallId;
    private byte[] lastFinalResponse;
    private InetSocketAddress lastFinalTarget;

    public SipClient(Config config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public boolean isRegistered() { synchronized (lock) { return registered; } }

    public void start() {
        running = true;
        thread = new Thread(this::run, "sip");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the network thread and waits briefly for it. Safe to call more than once. */
    public void stop() {
        running = false;
        synchronized (lock) {
            if (call != null) call.rtp.close();
            call = null;
            ringing = null;
        }
        DatagramSocket current = socket;
        if (current != null) current.close();
        Thread worker = thread;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Ends the active call with BYE. Returns false when there is none. */
    public boolean hangup() {
        Call ended;
        synchronized (lock) {
            if (call == null) return false;
            ended = call;
            sendQuiet(byeFor(ended), ended.source);
            finish(ended.invite.header("call-id"), ended.ok, ended.source);
        }
        notifyEnded("Hung up locally", ended);
        return true;
    }

    /** Sends 16 kHz mic audio to the caller of the active call (ignored when there is none). */
    public void sendAudio(short[] pcm16k) {
        RtpSession rtp;
        synchronized (lock) { rtp = call == null ? null : call.rtp; }
        if (rtp != null) rtp.writeMic(pcm16k);
    }

    /** True while an INVITE is ringing and waiting for {@link #acceptRinging} or {@link #declineRinging}. */
    public boolean isRinging() { synchronized (lock) { return ringing != null; } }

    /** Test hook: shorten the ring timeout. */
    void setRingTimeoutMs(long millis) { synchronized (lock) { ringTimeoutMs = millis; } }

    /** Answers the ringing INVITE with 200 OK into {@code sink}. False when nothing is ringing (cancelled or timed out). */
    public boolean acceptRinging(RtpSession.Sink sink) {
        Ringing r;
        synchronized (lock) {
            r = ringing;
            if (r == null) return false;
            ringing = null;
        }
        return establish(r.invite, r.source, r.offer, r.toTag, sink, r.caller);
    }

    /** Rejects the ringing INVITE (typically 603 Decline). False when nothing is ringing. */
    public boolean declineRinging(int code, String reason) {
        synchronized (lock) {
            Ringing r = ringing;
            if (r == null) return false;
            ringing = null;
            reject(r.invite, r.source, code, reason, r.toTag);
            return true;
        }
    }

    // ---- network thread ----

    private void run() {
        while (running) {
            try {
                open();
                long now = System.currentTimeMillis();
                if (now >= nextRegisterAt) {
                    sendRegister(null, null);
                    // Retry if the PBX never answers; a response replaces this deadline.
                    nextRegisterAt = now + RETRY_MS;
                }
                retransmitOk(now);
                expireRinging(now);
                receiveOnce();
            } catch (IOException | RuntimeException error) {
                if (!running) return;
                closeSocket();
                report(false, "Network error: " + error.getMessage());
                pause(NETWORK_RETRY_MS);
            }
        }
        closeSocket();
    }

    private void open() throws IOException {
        if (socket != null) return;
        String problem = config.problem();
        if (problem != null) throw new IOException(problem);
        serverAddress = InetAddress.getByName(config.server);
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(serverAddress, config.port);
            localIp = probe.getLocalAddress().getHostAddress();
        }
        if (localIp.contains(":") || localIp.equals("0.0.0.0")) throw new IOException("No local IPv4 address found for the server");
        DatagramSocket bound = new DatagramSocket(null);
        bound.setReuseAddress(true);
        bound.bind(new InetSocketAddress(config.localPort));
        bound.setSoTimeout(100);
        socket = bound;
        cseq = 0;
        lastRegisterCseq = -1;
        nextRegisterAt = 0;
    }

    private void closeSocket() {
        DatagramSocket current = socket;
        socket = null;
        if (current != null) current.close();
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            if (!running) Thread.currentThread().interrupt();
        }
    }

    private void receiveOnce() throws IOException {
        DatagramSocket current = socket;
        if (current == null) return;
        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
        try {
            current.receive(packet);
        } catch (SocketTimeoutException timeout) {
            return;
        }
        SipMessage message = SipMessage.parse(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
        if (message == null) return;
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        try {
            if (message.isRequest()) handleRequest(message, from);
            else handleResponse(message);
        } catch (RuntimeException error) {
            // A bug in message handling must not tear down the registration.
            listener.log("Unexpected error handling \"" + message.startLine + "\": " + error);
        }
    }

    /** RFC 3261 13.3.1.4: resend 200 OK until ACK, doubling from T1 up to T2, give up after 64*T1. */
    private void retransmitOk(long now) {
        Call timedOut = null;
        synchronized (lock) {
            if (call == null || call.acked || now < call.nextOkAt) return;
            if (now - call.okSentAt >= ANSWER_TIMEOUT_MS) {
                timedOut = call;
                sendQuiet(byeFor(call), call.source);
                finish(call.invite.header("call-id"), call.ok, call.source);
            } else {
                sendQuiet(call.ok, call.source);
                call.okInterval = Math.min(call.okInterval * 2, T2_MS);
                call.nextOkAt = now + call.okInterval;
            }
        }
        if (timedOut != null) notifyEnded("No ACK for 200 OK", timedOut);
    }

    private void expireRinging(long now) {
        Ringing expired;
        synchronized (lock) {
            if (ringing == null || now - ringing.startedAt < ringTimeoutMs) return;
            expired = ringing;
            ringing = null;
            reject(expired.invite, expired.source, 480, "Temporarily Unavailable", expired.toTag);
        }
        listener.log(expired.caller + ": ring timeout, sent 480");
        try { expired.handler.onCancelled("Ring timeout"); } catch (RuntimeException ignored) { }
    }

    // ---- registration ----

    private void sendRegister(String authName, String authValue) throws IOException {
        cseq++;
        lastRegisterCseq = cseq;
        lastRegisterHadAuth = authValue != null;
        String aor = "sip:" + config.user + "@" + config.server;
        StringBuilder sb = new StringBuilder()
            .append("REGISTER sip:").append(config.server).append(" SIP/2.0\r\n")
            .append("Via: SIP/2.0/UDP ").append(localIp).append(':').append(config.localPort)
            .append(";branch=").append(branch()).append(";rport\r\n")
            .append("Max-Forwards: 70\r\n")
            .append("From: <").append(aor).append(">;tag=").append(registerTag).append("\r\n")
            .append("To: <").append(aor).append(">\r\n")
            .append("Call-ID: ").append(registerCallId).append("\r\n")
            .append("CSeq: ").append(cseq).append(" REGISTER\r\n")
            .append("Contact: <sip:").append(config.user).append('@').append(localIp).append(':')
            .append(config.localPort).append(">\r\n")
            .append("Expires: ").append(config.expires).append("\r\n")
            .append("Allow: ").append(ALLOW).append("\r\n")
            .append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (authValue != null) sb.append(authName).append(": ").append(authValue).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        send(sb.toString().getBytes(StandardCharsets.UTF_8), new InetSocketAddress(serverAddress, config.port));
    }

    private void handleResponse(SipMessage message) throws IOException {
        if (!"REGISTER".equals(message.cseqMethod()) || message.cseqNumber() != lastRegisterCseq) return;
        int code = message.status();
        if (code < 200) return;
        if (code == 200) {
            int expires = registrationExpires(message);
            nextRegisterAt = System.currentTimeMillis() + Math.max(30, expires) * 500L;
            report(true, "Registered (expires in " + expires + " s)");
            return;
        }
        if (code == 401 || code == 407) {
            if (lastRegisterHadAuth) {
                failRegistration("Registration rejected; check the username or password");
                return;
            }
            String challenge = message.header(code == 401 ? "www-authenticate" : "proxy-authenticate"); // leak-ok: header names
            if (challenge == null) {
                failRegistration("Server requires authentication but sent no challenge");
                return;
            }
            Map<String, String> params = SipDigest.parseParams(challenge);
            String algorithm = params.get("algorithm");
            if (algorithm != null && !algorithm.equalsIgnoreCase("MD5")) {
                failRegistration("Digest algorithm " + algorithm + " is not supported (MD5 only)");
                return;
            }
            String qop = null;
            String offered = params.get("qop");
            if (offered != null) {
                for (String option : offered.split(",")) if (option.trim().equalsIgnoreCase("auth")) qop = "auth";
                if (qop == null) {
                    failRegistration("qop \"" + offered + "\" is not supported");
                    return;
                }
            }
            String realm = params.get("realm");
            String nonce = params.get("nonce");
            if (realm == null || nonce == null) {
                failRegistration("Challenge is missing realm or nonce");
                return;
            }
            String user = config.authUser.isEmpty() ? config.user : config.authUser;
            String uri = "sip:" + config.server;
            String cnonce = hex(8);
            String nc = "00000001";
            StringBuilder value = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", response=\"")
                .append(SipDigest.response(user, realm, config.password, "REGISTER", uri, nonce, qop, nc, cnonce))
                .append("\", algorithm=MD5");
            if (qop != null) value.append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
            if (params.get("opaque") != null) value.append(", opaque=\"").append(params.get("opaque")).append('"');
            sendRegister(code == 401 ? "Authorization" : "Proxy-Authorization", value.toString()); // leak-ok: header names
            return;
        }
        failRegistration("Registration failed: " + code + " " + message.reason());
    }

    private int registrationExpires(SipMessage message) {
        String header = message.header("expires");
        if (header != null) {
            try { return Integer.parseInt(header.trim()); } catch (NumberFormatException ignored) { }
        }
        String contact = message.header("contact");
        if (contact != null) {
            int at = contact.toLowerCase().indexOf("expires=");
            if (at >= 0) {
                StringBuilder digits = new StringBuilder();
                for (int i = at + 8; i < contact.length() && Character.isDigit(contact.charAt(i)); i++) digits.append(contact.charAt(i));
                if (digits.length() > 0) return Integer.parseInt(digits.toString());
            }
        }
        return config.expires;
    }

    private void failRegistration(String detail) {
        nextRegisterAt = System.currentTimeMillis() + RETRY_MS;
        report(false, detail);
    }

    private void report(boolean ok, String detail) {
        boolean changed;
        synchronized (lock) {
            changed = ok != registered || !detail.equals(lastDetail);
            registered = ok;
            lastDetail = detail;
        }
        if (changed) {
            try {
                listener.onRegistration(ok, detail);
            } catch (RuntimeException ignored) {
                // The host may already be revoked while the plugin shuts down.
            }
        }
    }

    // ---- incoming requests ----

    private void handleRequest(SipMessage message, InetSocketAddress from) {
        for (String required : new String[] {"via", "from", "to", "call-id", "cseq"}) {
            if (message.header(required) == null) return;
        }
        String method = message.method();
        if ("INVITE".equals(method)) {
            handleInvite(message, from);
            return;
        }
        String callId = message.header("call-id");
        Call ended = null;
        Ringing cancelled = null;
        synchronized (lock) {
            boolean ours = call != null && callId.equals(call.invite.header("call-id"));
            boolean ringingHere = ringing != null && callId.equals(ringing.invite.header("call-id"));
            if ("ACK".equals(method)) {
                if (ours) call.acked = true;
            } else if ("BYE".equals(method)) {
                if (ours) {
                    sendQuiet(response(message, 200, "OK", call.toTag, ""), from);
                    ended = call;
                    finish(callId, call.ok, call.source);
                } else {
                    sendQuiet(response(message, 481, "Call/Transaction Does Not Exist", hex(4), ""), from);
                }
            } else if ("CANCEL".equals(method) && ringingHere) {
                sendQuiet(response(message, 200, "OK", ringing.toTag, ""), from);
                reject(ringing.invite, ringing.source, 487, "Request Terminated", ringing.toTag);
                cancelled = ringing;
                ringing = null;
            } else if ("CANCEL".equals(method)) {
                // Page calls are answered at once, so a CANCEL can only cross our 200 OK; it has no effect then (RFC 3261 9.2).
                sendQuiet(response(message, ours ? 200 : 481, ours ? "OK" : "Call/Transaction Does Not Exist", hex(4), ""), from);
            } else if ("OPTIONS".equals(method)) {
                sendQuiet(response(message, 200, "OK", hex(4), ""), from);
            } else {
                sendQuiet(response(message, 501, "Not Implemented", hex(4), ""), from);
            }
        }
        if (ended != null) notifyEnded("Remote party ended the call", ended);
        if (cancelled != null) {
            listener.log(cancelled.caller + ": cancelled while ringing");
            try { cancelled.handler.onCancelled("Caller cancelled"); } catch (RuntimeException ignored) { }
        }
    }

    private void handleInvite(SipMessage message, InetSocketAddress from) {
        String callId = message.header("call-id");
        String caller = SipMessage.caller(message.header("from"));
        boolean busy;
        synchronized (lock) {
            if (call != null && callId.equals(call.invite.header("call-id"))) {
                if (message.cseqNumber() == call.invite.cseqNumber()) {
                    sendQuiet(call.ok, from); // retransmitted INVITE
                } else {
                    // re-INVITE (session refresh, media update): keep our side as is.
                    sendQuiet(response(message, 200, "OK", call.toTag,
                        Sdp.answer(rtpAddress(), call.rtp.port(), payloadOf(call), sessionId())), from);
                }
                return;
            }
            if (ringing != null && callId.equals(ringing.invite.header("call-id"))) {
                sendQuiet(response(ringing.invite, 180, "Ringing", ringing.toTag, ""), from); // retransmitted INVITE
                return;
            }
            if (callId.equals(lastFinalCallId) && lastFinalResponse != null) {
                sendQuiet(lastFinalResponse, lastFinalTarget);
                return;
            }
            busy = call != null || ringing != null;
            if (!busy) sendQuiet(response(message, 100, "Trying", null, ""), from);
            else reject(message, from, 486, "Busy Here");
        }
        if (busy) {
            listener.log(caller + ": rejected 486 (already in a call)");
            return;
        }

        String alertInfo = message.header("alert-info"), callInfo = message.header("call-info");
        if (alertInfo != null || callInfo != null) {
            listener.log(caller + ": Alert-Info: " + alertInfo + " | Call-Info: " + callInfo);
        }

        Sdp.Offer offer;
        try {
            offer = Sdp.parseOffer(message.body, CODECS);
        } catch (Exception error) {
            synchronized (lock) { reject(message, from, 488, "Not Acceptable Here"); }
            listener.log(caller + ": rejected 488 (" + error.getMessage() + ")");
            return;
        }

        Answer answer;
        try {
            answer = listener.onInvite(caller, Sdp.codecName(offer.payload), alertInfo, callInfo);
        } catch (RuntimeException error) {
            answer = Answer.unavailable();
        }
        if (answer == null) answer = Answer.unavailable();

        if (answer.ring != null) {
            synchronized (lock) {
                String toTag = hex(4);
                ringing = new Ringing(message, from, offer, toTag, caller, answer.ring);
                sendQuiet(response(message, 180, "Ringing", toTag, ""), from);
            }
            return;
        }
        if (answer.code != 200) {
            synchronized (lock) { reject(message, from, answer.code, answer.reason); }
            listener.log(caller + ": rejected " + answer.code + " " + answer.reason);
            return;
        }
        establish(message, from, offer, hex(4), answer.sink, caller);
    }

    /** Opens RTP and sends 200 OK for an INVITE. False (after answering 500) when no RTP port could be opened. */
    private boolean establish(SipMessage message, InetSocketAddress from, Sdp.Offer offer, String toTag, RtpSession.Sink sink, String caller) {
        RtpSession rtp;
        try {
            rtp = new RtpSession(rtpAddress(), new InetSocketAddress(offer.address, offer.port), offer.payload, sink);
        } catch (IOException error) {
            synchronized (lock) { reject(message, from, 500, "Server Internal Error", toTag); }
            listener.log(caller + ": could not open RTP port: " + error.getMessage());
            return false;
        }
        synchronized (lock) {
            byte[] ok = response(message, 200, "OK", toTag, Sdp.answer(rtpAddress(), rtp.port(), offer.payload, sessionId()));
            call = new Call(message, toTag, from, ok, rtp, caller);
            long now = System.currentTimeMillis();
            call.okSentAt = now;
            call.nextOkAt = now + T1_MS;
            rtp.start();
            sendQuiet(ok, from);
        }
        try { listener.onCallStarted(caller); } catch (RuntimeException ignored) { }
        return true;
    }

    /** Sends a final error response and remembers it for retransmissions. Call with lock held. */
    private void reject(SipMessage invite, InetSocketAddress from, int code, String reason) {
        reject(invite, from, code, reason, hex(4));
    }

    private void reject(SipMessage invite, InetSocketAddress from, int code, String reason, String toTag) {
        byte[] response = response(invite, code, reason, toTag, "");
        sendQuiet(response, from);
        lastFinalCallId = invite.header("call-id");
        lastFinalResponse = response;
        lastFinalTarget = from;
    }

    /** Ends the call and remembers its final response. Call with lock held. */
    private void finish(String callId, byte[] response, InetSocketAddress target) {
        if (call != null && callId.equals(call.invite.header("call-id"))) {
            call.rtp.close();
            call = null;
        }
        lastFinalCallId = callId;
        lastFinalResponse = response;
        lastFinalTarget = target;
    }

    private void notifyEnded(String reason, Call ended) {
        try { listener.onCallEnded(reason, ended.rtp.packets(), ended.rtp.dropped()); } catch (RuntimeException ignored) { }
    }

    // ---- message building ----

    private InetAddress rtpAddress() {
        try {
            return InetAddress.getByName(localIp);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static int payloadOf(Call call) {
        try {
            return Sdp.parseOffer(call.invite.body, CODECS).payload;
        } catch (Exception error) {
            return Sdp.PCMU;
        }
    }

    private long sessionId() { return random.nextInt() & 0x7fffffffL; }

    private byte[] byeFor(Call c) {
        return ("BYE " + contactUri(c.invite) + " SIP/2.0\r\n"
            + "Via: SIP/2.0/UDP " + localIp + ":" + config.localPort + ";branch=" + branch() + ";rport\r\n"
            + "Max-Forwards: 70\r\n"
            + "From: " + withTag(c.invite.header("to"), c.toTag) + "\r\n"
            + "To: " + c.invite.header("from") + "\r\n"
            + "Call-ID: " + c.invite.header("call-id") + "\r\n"
            + "CSeq: " + (c.invite.cseqNumber() + 1) + " BYE\r\n"
            + "User-Agent: " + USER_AGENT + "\r\n"
            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The remote target for in-dialog requests: the INVITE's Contact URI, else its From URI. */
    static String contactUri(SipMessage invite) {
        String header = invite.header("contact");
        if (header == null) header = invite.header("from");
        int lt = header.indexOf('<');
        if (lt >= 0) {
            int gt = header.indexOf('>', lt);
            return header.substring(lt + 1, gt < 0 ? header.length() : gt);
        }
        int semi = header.indexOf(';');
        return (semi >= 0 ? header.substring(0, semi) : header).trim();
    }

    private static String withTag(String header, String tag) {
        if (header == null) return "";
        return header.toLowerCase().contains(";tag=") ? header : header + ";tag=" + tag;
    }

    private byte[] response(SipMessage request, int code, String reason, String toTag, String body) {
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        for (String via : request.all("via")) sb.append("Via: ").append(via).append("\r\n");
        sb.append("From: ").append(request.header("from")).append("\r\n");
        String to = request.header("to");
        if (toTag != null && !to.contains("tag=")) to += ";tag=" + toTag;
        sb.append("To: ").append(to).append("\r\n")
          .append("Call-ID: ").append(request.header("call-id")).append("\r\n")
          .append("CSeq: ").append(request.header("cseq")).append("\r\n")
          .append("Contact: <sip:").append(config.user).append('@').append(localIp).append(':').append(config.localPort).append(">\r\n")
          .append("Allow: ").append(ALLOW).append("\r\n")
          .append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (!body.isEmpty()) sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n").append(body);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void send(byte[] data, InetSocketAddress target) throws IOException {
        DatagramSocket current = socket;
        if (current == null) throw new IOException("Socket closed");
        current.send(new DatagramPacket(data, data.length, target));
    }

    private void sendQuiet(byte[] data, InetSocketAddress target) {
        try {
            send(data, target);
        } catch (IOException error) {
            listener.log("Send failed: " + error.getMessage());
        }
    }

    private String branch() { return "z9hG4bK" + hex(6); }

    private String hex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
