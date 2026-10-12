// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Caller side of the Kiosk Satellite intercom (docs/protocol.md): identity check, then a one-way broadcast.
 * One client per kiosk; the key is held only in memory and never logged. Thread-safe.
 */
public final class IntercomClient {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FRAME_SAMPLES = 1280; // 80 ms
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    /** Statuses for a broadcast that did not start. Kiosk replies use the kiosk's own words. */
    public static final String UNREACHABLE = "unreachable", KEY = "key", TLS = "tls", OFF = "off", DND = "dnd",
        BUSY = "busy", REFUSED = "refused", ERROR = "error";

    /** How this client presents itself to the kiosk ({@code from} in the call). */
    public static final class Peer {
        public final String id, name, address, version;
        public final int port;

        /** {@code id}: 16 hex chars; {@code address}/{@code port}: where the kiosk would call back (Reply). */
        public Peer(String id, String name, String address, int port, String version) {
            this.id = id;
            this.name = name.length() > 80 ? name.substring(0, 80) : name;
            this.address = address;
            this.port = port;
            this.version = version;
        }

        Peer named(String other) { return new Peer(id, other, address, port, version); }
    }

    /** {@code GET /api/intercom/identity}. */
    public static final class Identity {
        public final String id, name, version, keyDigest;
        public final boolean enabled, dnd, tls;
        public final int port;

        Identity(Map<String, Object> json) {
            id = string(json.get("id"));
            name = string(json.get("name"));
            version = string(json.get("version"));
            keyDigest = string(json.get("key"));
            enabled = Boolean.TRUE.equals(json.get("enabled"));
            dnd = Boolean.TRUE.equals(json.get("dnd"));
            Object endpoint = json.get("endpoint");
            Map<?, ?> ep = endpoint instanceof Map ? (Map<?, ?>) endpoint : java.util.Collections.emptyMap();
            tls = Boolean.TRUE.equals(ep.get("tls"));
            Object p = ep.get("port");
            port = p instanceof Long && (Long) p >= 1 && (Long) p <= 65535 ? ((Long) p).intValue() : -1;
        }

        private static String string(Object value) { return value instanceof String ? (String) value : null; }
    }

    /** Result of {@link #broadcast} or {@link #call}: a running broadcast or a ringing call, or why there is none. */
    public static final class Outcome {
        public final String status, detail;
        public final Broadcast broadcast;
        public final Call call;

        Outcome(String status, String detail, Broadcast broadcast) { this(status, detail, broadcast, null); }

        Outcome(String status, String detail, Broadcast broadcast, Call call) {
            this.status = status;
            this.detail = detail;
            this.broadcast = broadcast;
            this.call = call;
        }

        public boolean started() { return broadcast != null || call != null; }
    }

    /** A call the kiosk accepted (ringing, auto or listening), not yet carrying audio. */
    private static final class Placed {
        final Outcome failed;
        final int port;
        final String call, status;

        Placed(Outcome failed, int port, String call, String status) {
            this.failed = failed; this.port = port; this.call = call; this.status = status;
        }
    }

    private final String host;
    private final int adminPort;
    private final String key;
    private final Peer self;
    private final Consumer<String> log;
    private int timeoutMs = 2000;
    private volatile CallbackServer callbacks;
    private Identity cachedIdentity;
    private long identityAt;

    /** {@code adminPort}: where identity is served (2324 by default in KS). */
    public IntercomClient(String host, int adminPort, String key, Peer self, Consumer<String> log) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Intercom key is empty");
        this.host = host;
        this.adminPort = adminPort;
        this.key = key;
        this.self = self;
        this.log = log;
    }

    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    /** Where the kiosk's answer/decline callbacks arrive; required for {@link #call}. */
    public void setCallbackServer(CallbackServer server) { this.callbacks = server; }

    /** Fetches identity, at most once a second (KS rate-limits it per client); a fresh copy within 1 s is reused. */
    public Identity identity() throws IOException {
        synchronized (this) {
            if (cachedIdentity != null && System.currentTimeMillis() - identityAt < 1100) return cachedIdentity;
        }
        Http.Response r = Http.request(host, adminPort, "GET", "/api/intercom/identity", null, null, timeoutMs);
        if (r.status != 200) throw new IOException("identity returned HTTP " + r.status);
        Identity identity;
        try {
            identity = new Identity(Json.object(r.body));
        } catch (IllegalArgumentException bad) {
            throw new IOException("identity: " + bad.getMessage());
        }
        synchronized (this) {
            cachedIdentity = identity;
            identityAt = System.currentTimeMillis();
        }
        return identity;
    }

    /**
     * Checks identity, places a broadcast call and opens its audio socket with talk on. Blocks for up to a few
     * timeouts. {@code callerName} is shown on the kiosk card (trimmed to 80 chars). {@code onKioskEnded} runs if the
     * kiosk ends it first (e.g. Dismiss on the card).
     */
    private Placed place(String kind, String callerName, java.util.function.Consumer<String> beforePost) {
        Identity id;
        try {
            id = identity();
        } catch (IOException failure) {
            return fail(UNREACHABLE, "identity: " + failure.getMessage());
        }
        if (!id.enabled) return fail(OFF, "intercom disabled on the kiosk");
        if (id.keyDigest == null || !id.keyDigest.equals(IntercomAuth.digest(key))) {
            return fail(KEY, "key digest mismatch: our intercom key differs from the kiosk's");
        }
        if (id.tls) return fail(TLS, "kiosk requires TLS; plaintext only for now");
        if (id.dnd) return fail(DND, "kiosk reports Do Not Disturb or Lockdown");
        if (id.port < 0) return fail(ERROR, "identity has no valid endpoint port");

        String call = UUID.randomUUID().toString();
        Peer from = self.named(callerName);
        Map<String, Object> fromJson = new LinkedHashMap<>();
        fromJson.put("id", from.id);
        fromJson.put("name", from.name);
        fromJson.put("address", from.address);
        fromJson.put("port", from.port);
        fromJson.put("version", from.version);
        fromJson.put("tls", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call", call);
        body.put("kind", kind);
        body.put("from", fromJson);

        if (beforePost != null) beforePost.accept(call);
        Http.Response r;
        try {
            r = Http.request(host, id.port, "POST", "/api/intercom/call", token(call), Json.write(body), timeoutMs);
        } catch (IOException failure) {
            return fail(UNREACHABLE, "call: " + failure.getMessage());
        }
        String status = null;
        try {
            Object s = Json.object(r.body).get("status");
            if (s instanceof String) status = (String) s;
        } catch (IllegalArgumentException ignored) { }
        if (r.status == 403) return fail(KEY, "kiosk rejected our key (HTTP 403)");
        if (r.status == 409 || r.status == 426) return fail(TLS, "encryption mismatch (HTTP " + r.status + ")");
        if (r.status != 200 || status == null) return fail(ERROR, "call returned HTTP " + r.status);
        switch (status) {
            case "ringing": case "auto": case "listening": break;
            case "busy": case "dnd": case "off": case "refused": case "key":
                return fail(status, "kiosk answered " + status);
            default:
                return fail(ERROR, "unexpected call status " + status);
        }
        return new Placed(null, id.port, call, status);
    }

    private static Placed fail(String status, String detail) {
        return new Placed(new Outcome(status, detail, null), 0, null, status);
    }

    public Outcome broadcast(String callerName, Runnable onKioskEnded) {
        Placed placed = place("broadcast", callerName, null);
        if (placed.failed != null) return placed.failed;
        int port = placed.port;
        String call = placed.call, status = placed.status;
        Broadcast broadcast = new Broadcast(this, port, call, onKioskEnded);
        try {
            broadcast.open();
        } catch (IOException failure) {
            hangup(port, call, "hangup");
            return new Outcome(ERROR, "audio socket: " + failure.getMessage(), null);
        }
        log.accept("broadcast " + shortId(call) + " started (" + status + ")");
        return new Outcome(status, "kiosk answered " + status, broadcast);
    }

    /**
     * Rings the kiosk with a call (the card shows Answer/Decline) and returns at once with a {@link Call} in the
     * outcome; the listener hears the answer, decline or end. Needs {@link #setCallbackServer}, and the {@link Peer}
     * given to the constructor must point at that server, since the kiosk calls back there. Blocks for up to a few
     * timeouts, like {@link #broadcast}.
     */
    public Outcome call(String callerName, Call.Listener listener) {
        CallbackServer server = callbacks;
        if (server == null) return new Outcome(ERROR, "no callback server configured for calls", null);
        // The Call registers for callbacks before the POST, so an instant answer (auto-answer kiosks) cannot be missed.
        Call[] created = new Call[1];
        Placed placed = place("call", callerName, id -> created[0] = new Call(this, server, 0, id, listener));
        if (placed.failed != null) {
            if (created[0] != null) created[0].discard();
            return placed.failed;
        }
        Call call = created[0];
        call.setPort(placed.port);
        log.accept("call " + shortId(placed.call) + " placed (" + placed.status + ")");
        return new Outcome(placed.status, "kiosk answered " + placed.status, null, call);
    }

    String token(String call) { return IntercomAuth.token(key, call, self.id); }

    String host() { return host; }

    int timeoutMs() { return timeoutMs; }

    void log(String message) { log.accept(message); }

    /** Best effort; {@code action} is hangup or cancel. */
    void hangup(int port, String call, String action) {
        try {
            Http.Response r = Http.request(host, port, "POST", "/api/intercom/call/" + call, token(call),
                "{\"action\":" + Json.quote(action) + "}", timeoutMs);
            if (r.status != 200) log.accept("call " + shortId(call) + " " + action + " → HTTP " + r.status);
        } catch (IOException failure) {
            log.accept("call " + shortId(call) + " " + action + " failed: " + failure.getMessage());
        }
    }

    static String shortId(String call) { return call.substring(0, 8); }
}
