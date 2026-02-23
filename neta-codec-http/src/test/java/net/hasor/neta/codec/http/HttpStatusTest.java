package net.hasor.neta.codec.http;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpStatus}.
 */
public class HttpStatusTest {

    @Test
    public void testStandardInformationalStatuses() {
        assertEquals(100, HttpStatus.CONTINUE.code());
        assertEquals("Continue", HttpStatus.CONTINUE.reasonPhrase());
        assertTrue(HttpStatus.CONTINUE.isInformational());
        assertEquals(1, HttpStatus.CONTINUE.codeClass());

        assertEquals(101, HttpStatus.SWITCHING_PROTOCOLS.code());
        assertEquals(102, HttpStatus.PROCESSING.code());
    }

    @Test
    public void testStandardSuccessStatuses() {
        assertEquals(200, HttpStatus.OK.code());
        assertEquals("OK", HttpStatus.OK.reasonPhrase());
        assertTrue(HttpStatus.OK.isSuccess());
        assertEquals(2, HttpStatus.OK.codeClass());

        assertEquals(201, HttpStatus.CREATED.code());
        assertEquals(202, HttpStatus.ACCEPTED.code());
        assertEquals(204, HttpStatus.NO_CONTENT.code());
        assertEquals(206, HttpStatus.PARTIAL_CONTENT.code());
    }

    @Test
    public void testStandardRedirectionStatuses() {
        assertEquals(301, HttpStatus.MOVED_PERMANENTLY.code());
        assertEquals(302, HttpStatus.FOUND.code());
        assertEquals(304, HttpStatus.NOT_MODIFIED.code());
        assertEquals(307, HttpStatus.TEMPORARY_REDIRECT.code());
        assertEquals(308, HttpStatus.PERMANENT_REDIRECT.code());

        assertTrue(HttpStatus.MOVED_PERMANENTLY.isRedirection());
        assertEquals(3, HttpStatus.MOVED_PERMANENTLY.codeClass());
    }

    @Test
    public void testStandardClientErrorStatuses() {
        assertEquals(400, HttpStatus.BAD_REQUEST.code());
        assertEquals("Bad Request", HttpStatus.BAD_REQUEST.reasonPhrase());
        assertEquals(401, HttpStatus.UNAUTHORIZED.code());
        assertEquals(403, HttpStatus.FORBIDDEN.code());
        assertEquals(404, HttpStatus.NOT_FOUND.code());
        assertEquals(405, HttpStatus.METHOD_NOT_ALLOWED.code());
        assertEquals(429, HttpStatus.TOO_MANY_REQUESTS.code());

        assertTrue(HttpStatus.BAD_REQUEST.isClientError());
        assertEquals(4, HttpStatus.BAD_REQUEST.codeClass());
    }

    @Test
    public void testStandardServerErrorStatuses() {
        assertEquals(500, HttpStatus.INTERNAL_SERVER_ERROR.code());
        assertEquals("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase());
        assertEquals(502, HttpStatus.BAD_GATEWAY.code());
        assertEquals(503, HttpStatus.SERVICE_UNAVAILABLE.code());
        assertEquals(504, HttpStatus.GATEWAY_TIMEOUT.code());

        assertTrue(HttpStatus.INTERNAL_SERVER_ERROR.isServerError());
        assertEquals(5, HttpStatus.INTERNAL_SERVER_ERROR.codeClass());
    }

    @Test
    public void testValueOfKnownCode() {
        assertSame(HttpStatus.OK, HttpStatus.valueOf(200));
        assertSame(HttpStatus.NOT_FOUND, HttpStatus.valueOf(404));
        assertSame(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.valueOf(500));
    }

    @Test
    public void testValueOfUnknownCode() {
        HttpStatus s = HttpStatus.valueOf(999);
        assertEquals(999, s.code());
        assertTrue(s.reasonPhrase().contains("999"));
    }

    @Test
    public void testValueOfWithReasonPhrase() {
        // Known code + matching reason phrase should return singleton
        assertSame(HttpStatus.OK, HttpStatus.valueOf(200, "OK"));
        // Known code + different reason phrase should return new instance
        HttpStatus s = HttpStatus.valueOf(200, "Custom");
        assertEquals(200, s.code());
        assertEquals("Custom", s.reasonPhrase());
        assertNotSame(HttpStatus.OK, s);
    }

    @Test
    public void testCategoryBooleans() {
        assertFalse(HttpStatus.OK.isInformational());
        assertTrue(HttpStatus.OK.isSuccess());
        assertFalse(HttpStatus.OK.isRedirection());
        assertFalse(HttpStatus.OK.isClientError());
        assertFalse(HttpStatus.OK.isServerError());

        assertTrue(HttpStatus.NOT_FOUND.isClientError());
        assertFalse(HttpStatus.NOT_FOUND.isSuccess());
    }

    @Test
    public void testToString() {
        assertEquals("200 OK", HttpStatus.OK.toString());
        assertEquals("404 Not Found", HttpStatus.NOT_FOUND.toString());
    }

    @Test
    public void testEquals() {
        // Equals is based on code only
        assertEquals(HttpStatus.OK, HttpStatus.valueOf(200, "Custom Reason"));
        assertNotEquals(HttpStatus.OK, HttpStatus.NOT_FOUND);
    }

    @Test
    public void testHashCode() {
        assertEquals(HttpStatus.OK.hashCode(), HttpStatus.valueOf(200, "Custom").hashCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorCodeTooLow() {
        new HttpStatus(99, "Too Low");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorCodeTooHigh() {
        new HttpStatus(1000, "Too High");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNullReason() {
        new HttpStatus(200, null);
    }
}
