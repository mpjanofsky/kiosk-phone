// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Buttons on the kiosk's call card (KS 2026.11.24+, {@code actions} on {@code /api/intercom/call}). Each button is
 * one setting, {@code Label | action} or {@code Label | action | mdi:icon}, where the action is
 * {@code dtmf:<digits>} (RFC 2833 in the RTP stream), {@code info:<digits>} (SIP INFO, application/dtmf-relay),
 * {@code url:<http(s) URL>} (POST) or {@code get:<http(s) URL>} (GET).
 */
public final class CallButtons {
    public static final int MAX = 3;

    public enum Kind { DTMF, INFO, POST, GET }

    public static final class Button {
        public final String id, label, icon, target;
        public final Kind kind;

        Button(String id, String label, String icon, Kind kind, String target) {
            this.id = id; this.label = label; this.icon = icon; this.kind = kind; this.target = target;
        }

        /** The entry for the {@code actions} list. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("label", label);
            if (!icon.isEmpty()) json.put("icon", icon);
            return json;
        }
    }

    private final List<Button> buttons;
    private final String[] callers;
    private final List<String> problems;

    /** {@code specs}: up to {@link #MAX} button settings (empty ones are skipped); {@code callers} as for video. */
    public CallButtons(List<String> specs, String callers) {
        List<Button> parsed = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (String raw : specs) {
            n++;
            String spec = raw == null ? "" : raw.trim();
            if (spec.isEmpty()) continue;
            if (parsed.size() == MAX) { bad.add("button " + n + ": at most " + MAX + " buttons"); continue; }
            try {
                parsed.add(parse("b" + n, spec));
            } catch (IllegalArgumentException wrong) {
                bad.add("button " + n + ": " + wrong.getMessage());
            }
        }
        buttons = Collections.unmodifiableList(parsed);
        problems = Collections.unmodifiableList(bad);
        String list = callers == null ? "" : callers.trim();
        this.callers = list.isEmpty() ? new String[0] : list.toLowerCase(Locale.ROOT).split("\\s*,\\s*");
    }

    static Button parse(String id, String spec) {
        String[] parts = spec.split("\\|");
        if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("expected 'Label | action' or 'Label | action | mdi:icon'");
        String label = parts[0].trim();
        if (label.isEmpty()) throw new IllegalArgumentException("label is empty");
        if (label.length() > 40) label = label.substring(0, 40);
        String action = parts[1].trim();
        String icon = parts.length == 3 ? parts[2].trim() : "";
        if (!icon.isEmpty() && !icon.startsWith("mdi:")) throw new IllegalArgumentException("icon must start with mdi:");
        int colon = action.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("action needs dtmf:, info:, url: or get:");
        String scheme = action.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String target = action.substring(colon + 1).trim();
        Kind kind;
        switch (scheme) {
            case "dtmf": kind = Kind.DTMF; break;
            case "info": kind = Kind.INFO; break;
            case "url": case "post": kind = Kind.POST; break;
            case "get": kind = Kind.GET; break;
            default: throw new IllegalArgumentException("unknown action '" + scheme + "'");
        }
        if (kind == Kind.DTMF || kind == Kind.INFO) {
            if (!target.matches("[0-9*#A-Da-d]{1,16}")) throw new IllegalArgumentException("DTMF digits must be 0-9, *, #, A-D");
            target = target.toUpperCase(Locale.ROOT);
        } else if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        return new Button(id, label, icon, kind, target);
    }

    /** Buttons for this caller; empty when none are set or the caller does not match. */
    public List<Button> forCaller(String caller) {
        if (buttons.isEmpty()) return Collections.emptyList();
        if (callers.length == 0) return buttons;
        String c = caller == null ? "" : caller.toLowerCase(Locale.ROOT);
        for (String part : callers) if (!part.isEmpty() && c.contains(part)) return buttons;
        return Collections.emptyList();
    }

    public Button byId(String id) {
        for (Button b : buttons) if (b.id.equals(id)) return b;
        return null;
    }

    /** Settings that could not be read, for the plugin status/log. */
    public List<String> problems() { return problems; }

    public static CallButtons none() { return new CallButtons(Collections.<String>emptyList(), ""); }
}
