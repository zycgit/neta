package net.hasor.neta.codec.http;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpMethod}.
 */
public class HttpMethodTest {

    @Test
    public void testStandardMethodConstants() {
        assertEquals("GET", HttpMethod.GET.name());
        assertEquals("POST", HttpMethod.POST.name());
        assertEquals("PUT", HttpMethod.PUT.name());
        assertEquals("DELETE", HttpMethod.DELETE.name());
        assertEquals("HEAD", HttpMethod.HEAD.name());
        assertEquals("OPTIONS", HttpMethod.OPTIONS.name());
        assertEquals("PATCH", HttpMethod.PATCH.name());
        assertEquals("TRACE", HttpMethod.TRACE.name());
        assertEquals("CONNECT", HttpMethod.CONNECT.name());
    }

    @Test
    public void testValueOfKnownMethods() {
        assertSame(HttpMethod.GET, HttpMethod.valueOf("GET"));
        assertSame(HttpMethod.POST, HttpMethod.valueOf("POST"));
        assertSame(HttpMethod.PUT, HttpMethod.valueOf("PUT"));
        assertSame(HttpMethod.DELETE, HttpMethod.valueOf("DELETE"));
        assertSame(HttpMethod.HEAD, HttpMethod.valueOf("HEAD"));
        assertSame(HttpMethod.OPTIONS, HttpMethod.valueOf("OPTIONS"));
        assertSame(HttpMethod.PATCH, HttpMethod.valueOf("PATCH"));
        assertSame(HttpMethod.TRACE, HttpMethod.valueOf("TRACE"));
        assertSame(HttpMethod.CONNECT, HttpMethod.valueOf("CONNECT"));
    }

    @Test
    public void testValueOfCaseInsensitive() {
        assertSame(HttpMethod.GET, HttpMethod.valueOf("get"));
        assertSame(HttpMethod.POST, HttpMethod.valueOf("post"));
        assertSame(HttpMethod.PUT, HttpMethod.valueOf("Put"));
    }

    @Test
    public void testValueOfWithWhitespace() {
        assertSame(HttpMethod.GET, HttpMethod.valueOf("  GET  "));
    }

    @Test
    public void testValueOfCustomMethod() {
        HttpMethod custom = HttpMethod.valueOf("PURGE");
        assertEquals("PURGE", custom.name());
        assertNotSame(HttpMethod.GET, custom);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfNull() {
        HttpMethod.valueOf(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfEmpty() {
        HttpMethod.valueOf("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfBlank() {
        HttpMethod.valueOf("   ");
    }

    @Test
    public void testToString() {
        assertEquals("GET", HttpMethod.GET.toString());
        assertEquals("POST", HttpMethod.POST.toString());
    }

    @Test
    public void testEquals() {
        assertEquals(HttpMethod.GET, new HttpMethod("GET"));
        assertEquals(HttpMethod.POST, new HttpMethod("POST"));
        assertNotEquals(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    public void testHashCode() {
        assertEquals(HttpMethod.GET.hashCode(), new HttpMethod("GET").hashCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNull() {
        new HttpMethod(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorEmpty() {
        new HttpMethod("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithControlChar() {
        new HttpMethod("G\0ET");
    }
}
