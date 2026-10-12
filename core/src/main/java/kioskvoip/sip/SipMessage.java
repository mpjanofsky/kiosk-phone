// SPDX-License-Identifier: Apache-2.0
// Adapted from meiser79/kiosk-satellite-sip-phone @ d4cec06 (Apache-2.0); changed: package name.
package kioskvoip.sip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal SIP message parser: start line, headers with compact forms resolved, and body. */
final class SipMessage {
    private static final Map<String, String> COMPACT = new LinkedHashMap<>();
    static {
        COMPACT.put("v", "via");
        COMPACT.put("f", "from");
        COMPACT.put("t", "to");
        COMPACT.put("i", "call-id");
        COMPACT.put("m", "contact");
        COMPACT.put("l", "content-length");
        COMPACT.put("c", "content-type");
    }

    final String startLine;
    final String body;
    private final Map<String, List<String>> headers;

    private SipMessage(String startLine, Map<String, List<String>> headers, String body) {
        this.startLine = startLine;
        this.headers = headers;
        this.body = body;
    }

    /** Returns null for datagrams that are not SIP. */
    static SipMessage parse(String text) {
        String normalized = text.replace("\r\n", "\n");
        int split = normalized.indexOf("\n\n");
        String head = split < 0 ? normalized : normalized.substring(0, split);
        String body = split < 0 ? "" : normalized.substring(split + 2);
        String[] lines = head.split("\n");
        if (lines.length == 0 || lines[0].trim().isEmpty() || !lines[0].contains("SIP/2.0")) return null;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String last = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) {
                List<String> values = headers.get(last);
                values.set(values.size() - 1, values.get(values.size() - 1) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String compact = COMPACT.get(name);
            if (compact != null) name = compact;
            List<String> values = headers.get(name);
            if (values == null) {
                values = new ArrayList<>();
                headers.put(name, values);
            }
            values.add(line.substring(colon + 1).trim());
            last = name;
        }
        return new SipMessage(lines[0].trim(), headers, body);
    }

    boolean isRequest() { return !startLine.startsWith("SIP/2.0"); }

    String method() { return isRequest() ? startLine.split(" ", 3)[0] : null; }

    int status() {
        if (isRequest()) return -1;
        try { return Integer.parseInt(startLine.split(" ", 3)[1]); } catch (RuntimeException error) { return -1; }
    }

    String reason() {
        String[] parts = startLine.split(" ", 3);
        return parts.length > 2 ? parts[2] : "";
    }

    String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    List<String> all(String name) {
        List<String> values = headers.get(name);
        return values == null ? new ArrayList<String>() : values;
    }

    int cseqNumber() {
        String cseq = header("cseq");
        if (cseq == null) return -1;
        try { return Integer.parseInt(cseq.trim().split("\\s+")[0]); } catch (RuntimeException error) { return -1; }
    }

    String cseqMethod() {
        String cseq = header("cseq");
        if (cseq == null) return "";
        String[] parts = cseq.trim().split("\\s+");
        return parts.length > 1 ? parts[1] : "";
    }

    /** "Name (user)" or just the user part of a From header, shortened for display. */
    static String caller(String from) {
        if (from == null) return "Unknown caller";
        String display = null;
        String uri = from;
        int lt = from.indexOf('<');
        if (lt >= 0) {
            String before = from.substring(0, lt).trim();
            if (before.length() >= 2 && before.startsWith("\"") && before.endsWith("\"")) {
                before = before.substring(1, before.length() - 1);
            }
            if (!before.isEmpty()) display = before;
            int gt = from.indexOf('>', lt);
            uri = from.substring(lt + 1, gt < 0 ? from.length() : gt);
        } else {
            int semi = from.indexOf(';');
            if (semi >= 0) uri = from.substring(0, semi);
        }
        int colon = uri.indexOf(':');
        String user = colon >= 0 ? uri.substring(colon + 1) : uri;
        int at = user.indexOf('@');
        if (at >= 0) user = user.substring(0, at);
        else {
            int semi = user.indexOf(';');
            if (semi >= 0) user = user.substring(0, semi);
        }
        String text = display != null && !display.equals(user) ? display + " (" + user + ")" : user;
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
