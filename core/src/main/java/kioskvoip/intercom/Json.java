// SPDX-License-Identifier: Apache-2.0
package kioskvoip.intercom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the intercom protocol, with no dependencies (org.json is Android-only).
 * Objects parse to Map, arrays to List, numbers to Long or Double, plus String, Boolean and null.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) { this.text = text; }

    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.pos != text.length()) throw json.error("trailing data");
        return value;
    }

    /** Parses a JSON object; anything else is an error. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("JSON: expected an object");
        return (Map<String, Object>) value;
    }

    /** JSON string literal, quotes included. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Compact JSON for maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringBuilder out = new StringBuilder();
        if (value instanceof Map) {
            out.append('{');
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(quote(String.valueOf(entry.getKey()))).append(':').append(write(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            out.append('[');
            for (Object item : (List<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(write(item));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("JSON: cannot write " + value.getClass().getSimpleName());
    }

    // ---- parser ----

    private Object value() {
        space();
        if (pos >= text.length()) throw error("unexpected end");
        char c = text.charAt(pos);
        if (c == '{') return objectValue();
        if (c == '[') return arrayValue();
        if (c == '"') return string();
        if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (text.startsWith("null", pos)) { pos += 4; return null; }
        return number();
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        space();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            space();
            if (peek() != '"') throw error("expected a key");
            String key = string();
            space();
            expect(':');
            map.put(key, value());
            space();
            if (peek() == ',') { pos++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> arrayValue() {
        List<Object> list = new ArrayList<>();
        pos++;
        space();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(value());
            space();
            if (peek() == ',') { pos++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (pos >= text.length()) throw error("bad escape");
            char e = text.charAt(pos++);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (pos + 4 > text.length()) throw error("bad escape");
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: out.append(e); // " \ /
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String token = text.substring(start, pos);
        if (token.isEmpty()) throw error("unexpected character");
        try {
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) return Long.parseLong(token);
            return Double.parseDouble(token);
        } catch (NumberFormatException failure) {
            throw error("bad number");
        }
    }

    private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        pos++;
    }

    private void space() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + pos);
    }
}
