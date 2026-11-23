package kioskvoip.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PhoneBookTest {
    @Test
    void parsesLinesAndSemicolons() {
        PhoneBook book = PhoneBook.parse("Reception = 5001\nKitchen=5002; Front Door =  *80 \n\n");
        assertEquals(3, book.entries().size());
        assertEquals("5001", book.find("reception").number);
        assertEquals("*80", book.find("Front  Door").number);
        assertTrue(book.problems().isEmpty());
    }

    @Test
    void parsesJsonRowsAndAcceptsNumericNumbers() {
        PhoneBook book = PhoneBook.parse("[{\"name\":\"Reception\",\"number\":\"5001\"},{\"name\":\"Office\",\"number\":7008}]");
        assertEquals(2, book.entries().size());
        assertEquals("7008", book.find("Office").number);
    }

    @Test
    void skipsBadRowsAndSaysWhy() {
        PhoneBook book = PhoneBook.parse("A = 1\nno equals sign\nB = 5 0\nA = 2\n = 7\nC = <x>");
        assertEquals(1, book.entries().size());
        assertEquals(5, book.problems().size());
        assertNull(book.find("B"));
    }

    @Test
    void badJsonAndEmptyAreHarmless() {
        assertTrue(PhoneBook.parse("[{").entries().isEmpty());
        assertEquals(1, PhoneBook.parse("[{").problems().size());
        assertTrue(PhoneBook.parse("").entries().isEmpty());
        assertTrue(PhoneBook.parse(null).problems().isEmpty());
        assertNull(PhoneBook.parse("A=1").find(null));
    }

    @Test
    void capsEntries() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("N").append(i).append('=').append(i).append('\n');
        PhoneBook book = PhoneBook.parse(sb.toString());
        assertEquals(32, book.entries().size());
        assertEquals(8, book.problems().size());
    }

    @Test
    void readsRowsAsKsDeliversThem() {
        java.util.Map<String, Object> a = new java.util.LinkedHashMap<>(), b = new java.util.LinkedHashMap<>();
        a.put("name", "Reception");
        a.put("number", "5001");
        b.put("name", "");
        b.put("number", "5002");
        PhoneBook book = PhoneBook.fromRows(java.util.Arrays.asList(a, b));
        assertEquals(1, book.entries().size());
        assertEquals(1, book.problems().size());
        assertTrue(PhoneBook.fromRows(null).entries().isEmpty());
        assertEquals(1, PhoneBook.fromRows("oops").problems().size());
    }
}
