package net.hasor.neta.codec.http;

import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpVersion}.
 */
public class HttpVersionTest {

    @Test
    public void testHttp10Constants() {
        HttpVersion v = HttpVersion.HTTP_1_0;
        assertEquals("HTTP", v.protocolName());
        assertEquals(1, v.majorVersion());
        assertEquals(0, v.minorVersion());
        assertEquals("HTTP/1.0", v.text());
        assertFalse(v.isKeepAliveDefault());
    }

    @Test
    public void testHttp11Constants() {
        HttpVersion v = HttpVersion.HTTP_1_1;
        assertEquals("HTTP", v.protocolName());
        assertEquals(1, v.majorVersion());
        assertEquals(1, v.minorVersion());
        assertEquals("HTTP/1.1", v.text());
        assertTrue(v.isKeepAliveDefault());
    }

    @Test
    public void testValueOfHttp10() {
        assertSame(HttpVersion.HTTP_1_0, HttpVersion.valueOf("HTTP/1.0"));
    }

    @Test
    public void testValueOfHttp11() {
        assertSame(HttpVersion.HTTP_1_1, HttpVersion.valueOf("HTTP/1.1"));
    }

    @Test
    public void testValueOfCaseInsensitive() {
        assertSame(HttpVersion.HTTP_1_1, HttpVersion.valueOf("http/1.1"));
        assertSame(HttpVersion.HTTP_1_0, HttpVersion.valueOf("Http/1.0"));
    }

    @Test
    public void testValueOfCustomVersion() {
        HttpVersion v = HttpVersion.valueOf("RTSP/2.0");
        assertEquals("RTSP", v.protocolName());
        assertEquals(2, v.majorVersion());
        assertEquals(0, v.minorVersion());
        assertEquals("RTSP/2.0", v.text());
    }

    @Test
    public void testValueOfWithWhitespace() {
        assertSame(HttpVersion.HTTP_1_1, HttpVersion.valueOf("  HTTP/1.1  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfNull() {
        HttpVersion.valueOf(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfEmpty() {
        HttpVersion.valueOf("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfInvalidNoSlash() {
        HttpVersion.valueOf("HTTP1.1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfInvalidNoDot() {
        HttpVersion.valueOf("HTTP/11");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValueOfInvalidNonNumeric() {
        HttpVersion.valueOf("HTTP/a.b");
    }

    @Test
    public void testToString() {
        assertEquals("HTTP/1.1", HttpVersion.HTTP_1_1.toString());
        assertEquals("HTTP/1.0", HttpVersion.HTTP_1_0.toString());
    }

    @Test
    public void testEquals() {
        assertEquals(HttpVersion.HTTP_1_1, new HttpVersion("HTTP", 1, 1, true));
        assertEquals(HttpVersion.HTTP_1_0, new HttpVersion("HTTP", 1, 0, false));
        assertNotEquals(HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1);
    }

    @Test
    public void testHashCode() {
        assertEquals(HttpVersion.HTTP_1_1.hashCode(), new HttpVersion("HTTP", 1, 1, true).hashCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNullProtocol() {
        new HttpVersion(null, 1, 1, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNegativeMajor() {
        new HttpVersion("HTTP", -1, 0, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNegativeMinor() {
        new HttpVersion("HTTP", 1, -1, true);
    }
}
