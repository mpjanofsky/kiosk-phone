// SPDX-License-Identifier: Apache-2.0
package kioskvoip.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kioskvoip.intercom.Json;

/**
 * The phone book setting: one entry per SIP target (a name shown on the kiosk and the number to dial). Accepts a JSON
 * array of {@code {"name":…,"number":…}} objects (how a Kiosk Satellite {@code list} setting is likely to arrive) or
 * plain text with one {@code Name = number} per line or {@code ;}-separated. Bad rows are skipped and reported, never fatal.
 */
public final class PhoneBook {
    /** Same limits as {@code SipClient.placeCall}: the number goes into a SIP URI. */
    private static final String NUMBER = "[0-9A-Za-z*#+._-]{1,40}";
    private static final int NAME_MAX = 40, ENTRIES_MAX = 32;

    public static final class Entry {
        public final String name, number;

        Entry(String name, String number) {
            this.name = name;
            this.number = number;
        }

        @Override public String toString() { return name + " = " + number; }
    }

    private final List<Entry> entries;
    private final List<String> problems;

    private PhoneBook(List<Entry> entries, List<String> problems) {
        this.entries = Collections.unmodifiableList(entries);
        this.problems = Collections.unmodifiableList(problems);
    }

    public List<Entry> entries() { return entries; }

    /** One line per skipped row, for the host log. */
    public List<String> problems() { return problems; }

    /** The entry with this name (case-insensitive), or null. */
    public Entry find(String name) {
        if (name == null) return null;
        String wanted = name.trim().replaceAll("\\s+", " ");
        for (Entry entry : entries) if (entry.name.equalsIgnoreCase(wanted)) return entry;
        return null;
    }

    /** Rows as KS delivers a {@code list} setting to {@code configure}: a List of Maps with {@code name} and {@code number}. */
    public static PhoneBook fromRows(Object rows) {
        List<Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (rows instanceof List) addRows((List<?>) rows, entries, problems, seen);
        else if (rows != null) problems.add("Phone book setting is not a list");
        return new PhoneBook(entries, problems);
    }

    public static PhoneBook parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("[")) {
            parseJson(trimmed, entries, problems, seen);
        } else if (!trimmed.isEmpty()) {
            for (String row : trimmed.split("[\\r\\n;]+")) {
                if (row.trim().isEmpty()) continue;
                int eq = row.indexOf('=');
                if (eq < 0) {
                    problems.add("Skipped \"" + shorten(row) + "\": expected Name = number");
                    continue;
                }
                add(row.substring(0, eq), row.substring(eq + 1), entries, problems, seen);
            }
        }
        return new PhoneBook(entries, problems);
    }

    private static void parseJson(String text, List<Entry> entries, List<String> problems, Set<String> seen) {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException bad) {
            problems.add("Phone book is not valid JSON");
            return;
        }
        if (!(parsed instanceof List)) {
            problems.add("Phone book JSON must be an array");
            return;
        }
        addRows((List<?>) parsed, entries, problems, seen);
    }

    private static void addRows(List<?> rows, List<Entry> entries, List<String> problems, Set<String> seen) {
        for (Object row : rows) {
            if (!(row instanceof Map)) {
                problems.add("Skipped a row that is not an object");
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) row;
            Object name = map.get("name"), number = map.get("number");
            add(name instanceof String ? (String) name : "", number instanceof String ? (String) number
                : number instanceof Number ? String.valueOf(((Number) number).longValue()) : "", entries, problems, seen);
        }
    }

    private static void add(String rawName, String rawNumber, List<Entry> entries, List<String> problems, Set<String> seen) {
        String name = rawName.trim().replaceAll("\\s+", " "), number = rawNumber.trim();
        if (name.isEmpty() || number.isEmpty()) {
            problems.add("Skipped a row with no name or number");
        } else if (name.length() > NAME_MAX) {
            problems.add("Skipped \"" + shorten(name) + "\": name longer than " + NAME_MAX + " characters");
        } else if (!number.matches(NUMBER)) {
            problems.add("Skipped \"" + shorten(name) + "\": number has characters a SIP address cannot use");
        } else if (!seen.add(name.toLowerCase())) {
            problems.add("Skipped \"" + shorten(name) + "\": name already used");
        } else if (entries.size() >= ENTRIES_MAX) {
            problems.add("Skipped \"" + shorten(name) + "\": more than " + ENTRIES_MAX + " entries");
        } else {
            entries.add(new Entry(name, number));
        }
    }

    private static String shorten(String text) {
        String t = text.trim();
        return t.length() > 30 ? t.substring(0, 29) + "…" : t;
    }
}
