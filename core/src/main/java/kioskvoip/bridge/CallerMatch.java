// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which callers a setting applies to, shared by <b>Callers with video</b> and <b>Callers with buttons</b>.
 *
 * <p>The setting is a comma-separated list. The caller text is {@code Name (number)} or just {@code number}, as
 * {@code SipMessage.caller} builds it. An entry matches when it equals the caller's number or name, ignoring case.
 * Leading {@code *} on the number are ignored on both sides, so {@code 620} and {@code **620} match each other: FRITZ!Box
 * internal numbers start with {@code **}. Only when no entry matches exactly is {@code *} read as a wildcard: a
 * trailing one means "starts with" ({@code 0611*}). For a name a leading one works too ({@code *R20K*} contains,
 * {@code *Door} ends with). On a number, leading {@code *} stay part of the number, so {@code **620} never matches
 * {@code **1620}. {@code *} alone matches every caller, as does an empty list. Immutable.
 */
public final class CallerMatch {
    private final List<String> entries;

    public CallerMatch(String setting) {
        List<String> list = new ArrayList<>();
        if (setting != null) {
            for (String part : setting.split(",")) {
                String entry = part.trim().toLowerCase(Locale.ROOT);
                if (!entry.isEmpty()) list.add(entry);
            }
        }
        entries = Collections.unmodifiableList(list);
    }

    /** True when the setting is empty, i.e. it applies to every caller. */
    public boolean everyone() { return entries.isEmpty(); }

    public boolean matches(String caller) {
        if (entries.isEmpty()) return true;
        String text = caller == null ? "" : caller.trim().toLowerCase(Locale.ROOT);
        String name = "", number = text;
        if (text.endsWith(")")) {
            int open = text.lastIndexOf(" (");
            if (open >= 0) {
                name = text.substring(0, open).trim();
                number = text.substring(open + 2, text.length() - 1).trim();
            }
        }
        String bare = stripStars(number);
        for (String entry : entries) {
            if (entry.equals(number) || entry.equals(name) || entry.equals(text)) return true;
            if (!bare.isEmpty() && stripStars(entry).equals(bare)) return true;
        }
        for (String entry : entries) {
            boolean tail = entry.endsWith("*");
            String core = entry.replaceAll("\\*+$", "");
            if (core.replace("*", "").isEmpty()) return true; // "*" alone: every caller, written out
            boolean head = core.startsWith("*") && !NUMBER.matcher(core).matches();
            if (head) core = core.replaceAll("^\\*+", "");
            else if (!tail) continue;
            else core = stripStars(core);
            for (String candidate : head ? new String[] {name, number} : new String[] {name, number, bare}) {
                if (candidate.isEmpty()) continue;
                if (head && tail ? candidate.contains(core)
                        : head ? candidate.endsWith(core)
                        : candidate.startsWith(core)) return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[0-9*#+]+");

    private static String stripStars(String s) { return s.replaceAll("^\\*+", ""); }
}
