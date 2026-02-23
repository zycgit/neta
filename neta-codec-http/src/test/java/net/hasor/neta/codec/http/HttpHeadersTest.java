package net.hasor.neta.codec.http;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpHeaders}.
 */
public class HttpHeadersTest {

    @Test
    public void testAddAndGet() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "text/html");
        assertEquals("text/html", h.get("Content-Type"));
    }

    @Test
    public void testCaseInsensitiveGet() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "text/html");
        assertEquals("text/html", h.get("content-type"));
        assertEquals("text/html", h.get("CONTENT-TYPE"));
        assertEquals("text/html", h.get("Content-type"));
    }

    @Test
    public void testGetReturnsFirstValue() {
        HttpHeaders h = new HttpHeaders();
        h.add("Accept", "text/html");
        h.add("Accept", "application/json");
        assertEquals("text/html", h.get("Accept"));
    }

    @Test
    public void testGetAllMultipleValues() {
        HttpHeaders h = new HttpHeaders();
        h.add("Accept", "text/html");
        h.add("Accept", "application/json");
        List<String> values = h.getAll("Accept");
        assertEquals(2, values.size());
        assertEquals("text/html", values.get(0));
        assertEquals("application/json", values.get(1));
    }

    @Test
    public void testGetAllNotExist() {
        HttpHeaders h = new HttpHeaders();
        List<String> values = h.getAll("Not-Exist");
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    public void testGetNotExist() {
        HttpHeaders h = new HttpHeaders();
        assertNull(h.get("Not-Exist"));
    }

    @Test
    public void testGetNull() {
        HttpHeaders h = new HttpHeaders();
        assertNull(h.get(null));
    }

    @Test
    public void testSetReplacesExisting() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "text/html");
        h.add("Content-Type", "text/plain");
        h.set("Content-Type", "application/json");
        assertEquals("application/json", h.get("Content-Type"));
        assertEquals(1, h.getAll("Content-Type").size());
    }

    @Test
    public void testSetMultipleValues() {
        HttpHeaders h = new HttpHeaders();
        h.add("Accept", "text/html");
        List<String> newValues = java.util.Arrays.asList("application/xml", "application/json");
        h.set("Accept", newValues);
        assertEquals(2, h.getAll("Accept").size());
        assertEquals("application/xml", h.get("Accept"));
    }

    @Test
    public void testRemove() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "text/html");
        h.add("Host", "example.com");
        h.remove("Content-Type");
        assertNull(h.get("Content-Type"));
        assertEquals("example.com", h.get("Host"));
    }

    @Test
    public void testRemoveCaseInsensitive() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "text/html");
        h.remove("content-type");
        assertNull(h.get("Content-Type"));
    }

    @Test
    public void testRemoveNull() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.remove(null);
        assertEquals("example.com", h.get("Host"));
    }

    @Test
    public void testContains() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        assertTrue(h.contains("Host"));
        assertTrue(h.contains("host"));
        assertFalse(h.contains("Not-Here"));
        assertFalse(h.contains(null));
    }

    @Test
    public void testContainsWithValue() {
        HttpHeaders h = new HttpHeaders();
        h.add("Connection", "keep-alive");
        assertTrue(h.contains("Connection", "keep-alive"));
        assertTrue(h.contains("Connection", "Keep-Alive"));
        assertFalse(h.contains("Connection", "close"));
    }

    @Test
    public void testContainsWithValueNull() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        assertFalse(h.contains(null, "value"));
        assertFalse(h.contains("Host", null));
    }

    @Test
    public void testGetInt() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Length", "42");
        assertEquals(42, h.getInt("Content-Length", -1));
    }

    @Test
    public void testGetIntDefault() {
        HttpHeaders h = new HttpHeaders();
        assertEquals(-1, h.getInt("Content-Length", -1));
    }

    @Test
    public void testGetIntInvalid() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Length", "not-a-number");
        assertEquals(-1, h.getInt("Content-Length", -1));
    }

    @Test
    public void testGetLong() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Length", "9999999999");
        assertEquals(9999999999L, h.getLong("Content-Length", -1L));
    }

    @Test
    public void testGetLongDefault() {
        HttpHeaders h = new HttpHeaders();
        assertEquals(-1L, h.getLong("Content-Length", -1L));
    }

    @Test
    public void testClear() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.add("Content-Type", "text/html");
        h.clear();
        assertTrue(h.isEmpty());
        assertEquals(0, h.size());
    }

    @Test
    public void testNames() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.add("Content-Type", "text/html");
        Set<String> names = h.names();
        assertEquals(2, names.size());
        assertTrue(names.contains("host"));
        assertTrue(names.contains("content-type"));
    }

    @Test
    public void testSize() {
        HttpHeaders h = new HttpHeaders();
        assertEquals(0, h.size());
        h.add("Accept", "text/html");
        assertEquals(1, h.size());
        h.add("Accept", "application/json");
        assertEquals(2, h.size());
        h.add("Host", "example.com");
        assertEquals(3, h.size());
    }

    @Test
    public void testIsEmpty() {
        HttpHeaders h = new HttpHeaders();
        assertTrue(h.isEmpty());
        h.add("Host", "example.com");
        assertFalse(h.isEmpty());
    }

    @Test
    public void testEntries() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.add("Accept", "text/html");
        h.add("Accept", "application/json");
        List<Map.Entry<String, String>> entries = h.entries();
        assertEquals(3, entries.size());
    }

    @Test
    public void testIterator() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.add("Accept", "text/html");
        int count = 0;
        for (Map.Entry<String, String> entry : h) {
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    public void testCopy() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        h.add("Accept", "text/html");
        HttpHeaders copy = h.copy();
        assertEquals(h, copy);
        // Modifying copy should not affect original
        copy.add("Content-Type", "text/plain");
        assertFalse(h.contains("Content-Type"));
    }

    @Test
    public void testAddHeaders() {
        HttpHeaders h1 = new HttpHeaders();
        h1.add("Host", "example.com");
        HttpHeaders h2 = new HttpHeaders();
        h2.add("Accept", "text/html");
        h1.add(h2);
        assertTrue(h1.contains("Host"));
        assertTrue(h1.contains("Accept"));
    }

    @Test
    public void testEmptyHeaders() {
        HttpHeaders empty = HttpHeaders.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertNull(empty.get("Host"));
        assertTrue(empty.getAll("Host").isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testEmptyHeadersReadOnly() {
        HttpHeaders.EMPTY.add("Host", "example.com");
    }

    @Test
    public void testEqualsAndHashCode() {
        HttpHeaders h1 = new HttpHeaders();
        h1.add("Host", "example.com");
        h1.add("Accept", "text/html");
        HttpHeaders h2 = new HttpHeaders();
        h2.add("Host", "example.com");
        h2.add("Accept", "text/html");
        assertEquals(h1, h2);
        assertEquals(h1.hashCode(), h2.hashCode());
    }

    @Test
    public void testNotEquals() {
        HttpHeaders h1 = new HttpHeaders();
        h1.add("Host", "example.com");
        HttpHeaders h2 = new HttpHeaders();
        h2.add("Host", "example.org");
        assertNotEquals(h1, h2);
    }

    @Test
    public void testToStringNotNull() {
        HttpHeaders h = new HttpHeaders();
        h.add("Host", "example.com");
        assertNotNull(h.toString());
        assertTrue(h.toString().contains("host"));
        assertTrue(h.toString().contains("example.com"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNullName() {
        new HttpHeaders().add(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNullValue() {
        new HttpHeaders().add("Host", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetNullName() {
        new HttpHeaders().set(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetNullValue() {
        new HttpHeaders().set("Host", (String) null);
    }
}
