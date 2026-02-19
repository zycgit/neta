/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.cookie;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the cookie package:
 * {@link DefaultCookie}, {@link CookieDecoder}, {@link ServerCookieDecoder},
 * {@link CookieEncoder}, {@link ServerCookieEncoder}.
 */
public class CookieTest {

    // =========================================================================
    // DefaultCookie – construction and attribute accessors
    // =========================================================================

    private static ByteBuf toBuf(String s) {
        return ByteBuf.wrap(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String bufToString(ByteBuf buf) {
        buf.markWriter();
        return buf.getString(0, buf.readableBytes(), StandardCharsets.US_ASCII);
    }

    @Test
    public void testDefaultCookieBasicConstruction() {
        DefaultCookie c = new DefaultCookie("session", "abc123");
        assertEquals("session", c.name());
        assertEquals("abc123", c.value());
        assertNull(c.domain());
        assertNull(c.path());
        assertEquals(DefaultCookie.UNDEFINED_MAX_AGE, c.maxAge());
        assertNull(c.expires());
        assertFalse(c.isSecure());
        assertFalse(c.isHttpOnly());
        assertNull(c.sameSite());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultCookieNullNameThrows() {
        new DefaultCookie(null, "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultCookieEmptyNameThrows() {
        new DefaultCookie("", "value");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultCookieNullValueThrows() {
        new DefaultCookie("name", null);
    }

    @Test
    public void testDefaultCookieBuilderChain() {
        DefaultCookie c = new DefaultCookie("token", "xyz").setDomain("example.com").setPath("/api").setMaxAge(7200L).setExpires("Thu, 01 Jan 2099 00:00:00 GMT").setSecure(true).setHttpOnly(true).setSameSite("Strict");

        assertEquals("example.com", c.domain());
        assertEquals("/api", c.path());
        assertEquals(7200L, c.maxAge());
        assertEquals("Thu, 01 Jan 2099 00:00:00 GMT", c.expires());
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());
        assertEquals("Strict", c.sameSite());
    }

    @Test
    public void testDefaultCookieSetValue() {
        DefaultCookie c = new DefaultCookie("k", "old");
        c.setValue("new");
        assertEquals("new", c.value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultCookieSetNullValueThrows() {
        new DefaultCookie("k", "v").setValue(null);
    }

    @Test
    public void testDefaultCookieMaxAgeZeroMeansDelete() {
        DefaultCookie c = new DefaultCookie("k", "v").setMaxAge(0);
        assertEquals(0L, c.maxAge());
    }

    @Test
    public void testDefaultCookieToStringMinimal() {
        DefaultCookie c = new DefaultCookie("name", "value");
        assertEquals("name=value", c.toString());
    }

    @Test
    public void testDefaultCookieToStringFull() {
        DefaultCookie c = new DefaultCookie("session", "abc").setDomain("example.com").setPath("/").setMaxAge(3600).setExpires("Mon, 01 Jan 2099 00:00:00 GMT").setSecure(true).setHttpOnly(true).setSameSite("Lax");
        String s = c.toString();
        assertTrue(s.contains("session=abc"));
        assertTrue(s.contains("Domain=example.com"));
        assertTrue(s.contains("Path=/"));
        assertTrue(s.contains("Max-Age=3600"));
        assertTrue(s.contains("Expires=Mon, 01 Jan 2099 00:00:00 GMT"));
        assertTrue(s.contains("Secure"));
        assertTrue(s.contains("HttpOnly"));
        assertTrue(s.contains("SameSite=Lax"));
    }

    @Test
    public void testDefaultCookieEqualsSameNameValue() {
        DefaultCookie c1 = new DefaultCookie("k", "v");
        DefaultCookie c2 = new DefaultCookie("k", "v");
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    // =========================================================================
    // CookieDecoder – parsing the request Cookie header
    // =========================================================================

    @Test
    public void testDefaultCookieNotEqualsDifferentValue() {
        DefaultCookie c1 = new DefaultCookie("k", "v1");
        DefaultCookie c2 = new DefaultCookie("k", "v2");
        assertNotEquals(c1, c2);
    }

    @Test
    public void testDefaultCookieNotEqualsNonCookie() {
        DefaultCookie c = new DefaultCookie("k", "v");
        assertNotEquals(c, "k=v");
    }

    @Test
    public void testCookieDecoderSingleCookie() {
        List<Cookie> cookies = CookieDecoder.decode("session=abc123");
        assertEquals(1, cookies.size());
        assertEquals("session", cookies.get(0).name());
        assertEquals("abc123", cookies.get(0).value());
    }

    @Test
    public void testCookieDecoderMultipleCookies() {
        List<Cookie> cookies = CookieDecoder.decode("a=1; b=2; c=3");
        assertEquals(3, cookies.size());
        assertEquals("a", cookies.get(0).name());
        assertEquals("1", cookies.get(0).value());
        assertEquals("b", cookies.get(1).name());
        assertEquals("2", cookies.get(1).value());
        assertEquals("c", cookies.get(2).name());
        assertEquals("3", cookies.get(2).value());
    }

    @Test
    public void testCookieDecoderNullReturnsEmpty() {
        List<Cookie> cookies = CookieDecoder.decode((String) null);
        assertTrue(cookies.isEmpty());
    }

    @Test
    public void testCookieDecoderEmptyStringReturnsEmpty() {
        List<Cookie> cookies = CookieDecoder.decode("");
        assertTrue(cookies.isEmpty());
    }

    @Test
    public void testCookieDecoderQuotedValue() {
        List<Cookie> cookies = CookieDecoder.decode("token=\"bearer-xyz\"");
        assertEquals(1, cookies.size());
        assertEquals("bearer-xyz", cookies.get(0).value());
    }

    @Test
    public void testCookieDecoderEmptyValue() {
        List<Cookie> cookies = CookieDecoder.decode("key=");
        assertEquals(1, cookies.size());
        assertEquals("key", cookies.get(0).name());
        assertEquals("", cookies.get(0).value());
    }

    @Test
    public void testCookieDecoderMalformedTokenSkipped() {
        // No '=' means it cannot be parsed as a valid cookie
        List<Cookie> cookies = CookieDecoder.decode("noequals; valid=ok");
        assertEquals(1, cookies.size());
        assertEquals("valid", cookies.get(0).name());
        assertEquals("ok", cookies.get(0).value());
    }

    @Test
    public void testCookieDecoderValueWithEquals() {
        // Value that itself contains '=' (e.g. a Base64 string)
        List<Cookie> cookies = CookieDecoder.decode("data=abc==");
        assertEquals(1, cookies.size());
        assertEquals("data", cookies.get(0).name());
        assertEquals("abc==", cookies.get(0).value());
    }

    // =========================================================================
    // ServerCookieDecoder – parsing the Set-Cookie response header
    // =========================================================================

    @Test
    public void testCookieDecoderTrimsWhitespace() {
        List<Cookie> cookies = CookieDecoder.decode("  x = hello  ;  y = world  ");
        assertEquals(2, cookies.size());
        assertEquals("x", cookies.get(0).name());
        assertEquals("hello", cookies.get(0).value());
        assertEquals("y", cookies.get(1).name());
        assertEquals("world", cookies.get(1).value());
    }

    @Test
    public void testCookieDecoderResultIsUnmodifiable() {
        List<Cookie> cookies = CookieDecoder.decode("k=v");
        try {
            cookies.add(new DefaultCookie("x", "y"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void testServerCookieDecoderMinimal() {
        DefaultCookie c = ServerCookieDecoder.decode("session=abc");
        assertNotNull(c);
        assertEquals("session", c.name());
        assertEquals("abc", c.value());
        assertNull(c.domain());
        assertNull(c.path());
        assertEquals(DefaultCookie.UNDEFINED_MAX_AGE, c.maxAge());
        assertFalse(c.isSecure());
        assertFalse(c.isHttpOnly());
    }

    @Test
    public void testServerCookieDecoderFullAttributes() {
        String header = "session=abc; Domain=example.com; Path=/api; Max-Age=3600; " + "Expires=Thu, 01 Jan 2099 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict";
        DefaultCookie c = ServerCookieDecoder.decode(header);
        assertNotNull(c);
        assertEquals("session", c.name());
        assertEquals("abc", c.value());
        assertEquals("example.com", c.domain());
        assertEquals("/api", c.path());
        assertEquals(3600L, c.maxAge());
        assertEquals("Thu, 01 Jan 2099 00:00:00 GMT", c.expires());
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());
        assertEquals("Strict", c.sameSite());
    }

    @Test
    public void testServerCookieDecoderCaseInsensitiveAttributes() {
        DefaultCookie c = ServerCookieDecoder.decode("k=v; SECURE; HTTPONLY; SAMESITE=lax; PATH=/; DOMAIN=foo.com; MAX-AGE=60");
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());
        assertEquals("lax", c.sameSite());
        assertEquals("/", c.path());
        assertEquals("foo.com", c.domain());
        assertEquals(60L, c.maxAge());
    }

    @Test
    public void testServerCookieDecoderNullReturnsNull() {
        assertNull(ServerCookieDecoder.decode((String) null));
    }

    @Test
    public void testServerCookieDecoderEmptyReturnsNull() {
        assertNull(ServerCookieDecoder.decode(""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServerCookieDecoderMissingEqualsThrows() {
        ServerCookieDecoder.decode("noequalssign");
    }

    @Test
    public void testServerCookieDecoderQuotedValue() {
        DefaultCookie c = ServerCookieDecoder.decode("token=\"secret-value\"");
        assertEquals("secret-value", c.value());
    }

    @Test
    public void testServerCookieDecoderZeroMaxAge() {
        DefaultCookie c = ServerCookieDecoder.decode("old=gone; Max-Age=0");
        assertEquals(0L, c.maxAge());
    }

    @Test
    public void testServerCookieDecoderUnknownAttributeIgnored() {
        // RFC 6265: unknown attributes are silently ignored
        DefaultCookie c = ServerCookieDecoder.decode("x=y; FutureAttr=somevalue");
        assertEquals("x", c.name());
        assertEquals("y", c.value());
    }

    // =========================================================================
    // CookieEncoder – building the request Cookie header value
    // =========================================================================

    @Test
    public void testServerCookieDecoderSameSiteLax() {
        DefaultCookie c = ServerCookieDecoder.decode("id=1; SameSite=Lax");
        assertEquals("Lax", c.sameSite());
    }

    @Test
    public void testServerCookieDecoderSameSiteNone() {
        DefaultCookie c = ServerCookieDecoder.decode("id=1; SameSite=None; Secure");
        assertEquals("None", c.sameSite());
        assertTrue(c.isSecure());
    }

    @Test
    public void testCookieEncoderSingleCookie() {
        Cookie c = new DefaultCookie("session", "abc");
        assertEquals("session=abc", CookieEncoder.encode(c));
    }

    @Test
    public void testCookieEncoderMultipleCookiesVararg() {
        Cookie c1 = new DefaultCookie("a", "1");
        Cookie c2 = new DefaultCookie("b", "2");
        Cookie c3 = new DefaultCookie("c", "3");
        assertEquals("a=1; b=2; c=3", CookieEncoder.encode(c1, c2, c3));
    }

    @Test
    public void testCookieEncoderCollection() {
        List<Cookie> list = Arrays.asList(new DefaultCookie("x", "10"), new DefaultCookie("y", "20"));
        assertEquals("x=10; y=20", CookieEncoder.encode(list));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCookieEncoderNullThrows() {
        CookieEncoder.encode((Cookie[]) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCookieEncoderEmptyArrayThrows() {
        CookieEncoder.encode();
    }

    // =========================================================================
    // ServerCookieEncoder – building the response Set-Cookie header value
    // =========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testCookieEncoderEmptyCollectionThrows() {
        CookieEncoder.encode(Collections.emptyList());
    }

    @Test
    public void testCookieEncoderOnlyTransmitsNameAndValue() {
        // Encoder for request Cookie header must NOT include attributes like Path, Domain
        DefaultCookie c = new DefaultCookie("s", "v").setPath("/").setDomain("a.com").setHttpOnly(true);
        String encoded = CookieEncoder.encode(c);
        assertEquals("s=v", encoded);
        assertFalse(encoded.contains("Path"));
        assertFalse(encoded.contains("Domain"));
        assertFalse(encoded.contains("HttpOnly"));
    }

    @Test
    public void testServerCookieEncoderMinimal() {
        Cookie c = new DefaultCookie("k", "v");
        assertEquals("k=v", ServerCookieEncoder.encode(c));
    }

    @Test
    public void testServerCookieEncoderWithAllAttributes() {
        DefaultCookie c = new DefaultCookie("session", "abc").setDomain("example.com").setPath("/").setMaxAge(3600).setExpires("Mon, 01 Jan 2099 00:00:00 GMT").setSecure(true).setHttpOnly(true).setSameSite("Lax");

        String encoded = ServerCookieEncoder.encode(c);
        assertEquals("session=abc; Domain=example.com; Path=/; Max-Age=3600; " + "Expires=Mon, 01 Jan 2099 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax", encoded);
    }

    @Test
    public void testServerCookieEncoderSecureOnly() {
        DefaultCookie c = new DefaultCookie("k", "v").setSecure(true);
        String encoded = ServerCookieEncoder.encode(c);
        assertEquals("k=v; Secure", encoded);
    }

    @Test
    public void testServerCookieEncoderHttpOnlyOnly() {
        DefaultCookie c = new DefaultCookie("k", "v").setHttpOnly(true);
        String encoded = ServerCookieEncoder.encode(c);
        assertEquals("k=v; HttpOnly", encoded);
    }

    @Test
    public void testServerCookieEncoderMaxAgeZero() {
        DefaultCookie c = new DefaultCookie("old", "gone").setMaxAge(0);
        String encoded = ServerCookieEncoder.encode(c);
        assertTrue(encoded.contains("Max-Age=0"));
    }

    @Test
    public void testServerCookieEncoderMaxAgeUndefinedNotIncluded() {
        DefaultCookie c = new DefaultCookie("k", "v");
        assertFalse(ServerCookieEncoder.encode(c).contains("Max-Age"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServerCookieEncoderNullThrows() {
        ServerCookieEncoder.encode(null);
    }

    @Test
    public void testServerCookieEncoderDomainOnly() {
        DefaultCookie c = new DefaultCookie("k", "v").setDomain("sub.example.com");
        assertTrue(ServerCookieEncoder.encode(c).contains("Domain=sub.example.com"));
    }

    // =========================================================================
    // Round-trip: encode then decode
    // =========================================================================

    @Test
    public void testServerCookieEncoderPathOnly() {
        DefaultCookie c = new DefaultCookie("k", "v").setPath("/admin");
        assertTrue(ServerCookieEncoder.encode(c).contains("Path=/admin"));
    }

    @Test
    public void testServerCookieEncoderSameSiteStrict() {
        DefaultCookie c = new DefaultCookie("k", "v").setSameSite("Strict");
        assertTrue(ServerCookieEncoder.encode(c).contains("SameSite=Strict"));
    }

    @Test
    public void testRoundTripRequestCookie() {
        Cookie original1 = new DefaultCookie("lang", "en");
        Cookie original2 = new DefaultCookie("theme", "dark");

        String headerValue = CookieEncoder.encode(original1, original2);
        List<Cookie> decoded = CookieDecoder.decode(headerValue);

        assertEquals(2, decoded.size());
        assertEquals("lang", decoded.get(0).name());
        assertEquals("en", decoded.get(0).value());
        assertEquals("theme", decoded.get(1).name());
        assertEquals("dark", decoded.get(1).value());
    }

    @Test
    public void testRoundTripResponseCookie() {
        DefaultCookie original = new DefaultCookie("session", "s3cr3t").setDomain("example.com").setPath("/").setMaxAge(86400).setSecure(true).setHttpOnly(true).setSameSite("Lax");

        String headerValue = ServerCookieEncoder.encode(original);
        DefaultCookie decoded = ServerCookieDecoder.decode(headerValue);

        assertNotNull(decoded);
        assertEquals("session", decoded.name());
        assertEquals("s3cr3t", decoded.value());
        assertEquals("example.com", decoded.domain());
        assertEquals("/", decoded.path());
        assertEquals(86400L, decoded.maxAge());
        assertTrue(decoded.isSecure());
        assertTrue(decoded.isHttpOnly());
        assertEquals("Lax", decoded.sameSite());
    }

    // =========================================================================
    // Integration: read Cookie from HttpHeaders, write Set-Cookie to HttpHeaders
    // =========================================================================

    @Test
    public void testRoundTripMinimalCookie() {
        DefaultCookie original = new DefaultCookie("key", "val");
        String headerValue = ServerCookieEncoder.encode(original);
        DefaultCookie decoded = ServerCookieDecoder.decode(headerValue);
        assertEquals("key", decoded.name());
        assertEquals("val", decoded.value());
        assertEquals(DefaultCookie.UNDEFINED_MAX_AGE, decoded.maxAge());
        assertFalse(decoded.isSecure());
        assertFalse(decoded.isHttpOnly());
    }

    @Test
    public void testRoundTripDeleteCookie() {
        // Deleting a cookie: Max-Age=0
        DefaultCookie del = new DefaultCookie("expired", "").setMaxAge(0).setPath("/");
        String headerValue = ServerCookieEncoder.encode(del);
        DefaultCookie decoded = ServerCookieDecoder.decode(headerValue);
        assertEquals(0L, decoded.maxAge());
        assertEquals("/", decoded.path());
    }

    @Test
    public void testIntegrationReadCookieFromHeader() {
        net.hasor.neta.codec.http.HttpHeaders headers = new net.hasor.neta.codec.http.HttpHeaders();
        headers.add("Cookie", "user=bob; role=admin");

        String cookieHeader = headers.get("cookie");
        List<Cookie> cookies = CookieDecoder.decode(cookieHeader);

        assertEquals(2, cookies.size());
        assertEquals("user", cookies.get(0).name());
        assertEquals("bob", cookies.get(0).value());
        assertEquals("role", cookies.get(1).name());
        assertEquals("admin", cookies.get(1).value());
    }

    // =========================================================================
    // ByteBuf-based CookieDecoder tests
    // =========================================================================

    @Test
    public void testIntegrationWriteSetCookieToHeader() {
        net.hasor.neta.codec.http.HttpHeaders headers = new net.hasor.neta.codec.http.HttpHeaders();
        DefaultCookie c = new DefaultCookie("session", "tok").setPath("/").setHttpOnly(true).setMaxAge(1800);
        headers.add("Set-Cookie", ServerCookieEncoder.encode(c));

        String setCookieHeader = headers.get("set-cookie");
        DefaultCookie decoded = ServerCookieDecoder.decode(setCookieHeader);

        assertNotNull(decoded);
        assertEquals("tok", decoded.value());
        assertEquals("/", decoded.path());
        assertTrue(decoded.isHttpOnly());
        assertEquals(1800L, decoded.maxAge());
    }

    @Test
    public void testIntegrationMultipleSetCookieHeaders() {
        net.hasor.neta.codec.http.HttpHeaders headers = new net.hasor.neta.codec.http.HttpHeaders();
        headers.add("Set-Cookie", ServerCookieEncoder.encode(new DefaultCookie("a", "1").setPath("/")));
        headers.add("Set-Cookie", ServerCookieEncoder.encode(new DefaultCookie("b", "2").setSecure(true)));

        List<String> setCookies = headers.getAll("Set-Cookie");
        assertEquals(2, setCookies.size());

        DefaultCookie c1 = ServerCookieDecoder.decode(setCookies.get(0));
        DefaultCookie c2 = ServerCookieDecoder.decode(setCookies.get(1));

        assertEquals("a", c1.name());
        assertEquals("/", c1.path());
        assertEquals("b", c2.name());
        assertTrue(c2.isSecure());
    }

    @Test
    public void testByteBufCookieDecoderSingleCookie() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("session=abc123"));
        assertEquals(1, cookies.size());
        assertEquals("session", cookies.get(0).name());
        assertEquals("abc123", cookies.get(0).value());
    }

    @Test
    public void testByteBufCookieDecoderMultipleCookies() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("a=1; b=2; c=3"));
        assertEquals(3, cookies.size());
        assertEquals("a", cookies.get(0).name());
        assertEquals("1", cookies.get(0).value());
        assertEquals("b", cookies.get(1).name());
        assertEquals("2", cookies.get(1).value());
        assertEquals("c", cookies.get(2).name());
        assertEquals("3", cookies.get(2).value());
    }

    @Test
    public void testByteBufCookieDecoderNullReturnsEmpty() {
        assertTrue(CookieDecoder.decode((ByteBuf) null).isEmpty());
    }

    @Test
    public void testByteBufCookieDecoderQuotedValue() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("token=\"bearer-xyz\""));
        assertEquals(1, cookies.size());
        assertEquals("bearer-xyz", cookies.get(0).value());
    }

    @Test
    public void testByteBufCookieDecoderEmptyValue() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("key="));
        assertEquals(1, cookies.size());
        assertEquals("key", cookies.get(0).name());
        assertEquals("", cookies.get(0).value());
    }

    @Test
    public void testByteBufCookieDecoderMalformedTokenSkipped() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("noequals; valid=ok"));
        assertEquals(1, cookies.size());
        assertEquals("valid", cookies.get(0).name());
    }

    @Test
    public void testByteBufCookieDecoderTrimsWhitespace() {
        List<Cookie> cookies = CookieDecoder.decode(toBuf("  x = hello  ;  y = world  "));
        assertEquals(2, cookies.size());
        assertEquals("x", cookies.get(0).name());
        assertEquals("hello", cookies.get(0).value());
        assertEquals("y", cookies.get(1).name());
        assertEquals("world", cookies.get(1).value());
    }

    // =========================================================================
    // ByteBuf-based ServerCookieDecoder tests
    // =========================================================================

    @Test
    public void testByteBufServerCookieDecoderMinimal() {
        DefaultCookie c = ServerCookieDecoder.decode(toBuf("session=abc"));
        assertNotNull(c);
        assertEquals("session", c.name());
        assertEquals("abc", c.value());
    }

    @Test
    public void testByteBufServerCookieDecoderFullAttributes() {
        String header = "session=abc; Domain=example.com; Path=/api; Max-Age=3600; Expires=Thu, 01 Jan 2099 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict";
        DefaultCookie c = ServerCookieDecoder.decode(toBuf(header));
        assertNotNull(c);
        assertEquals("session", c.name());
        assertEquals("abc", c.value());
        assertEquals("example.com", c.domain());
        assertEquals("/api", c.path());
        assertEquals(3600L, c.maxAge());
        assertEquals("Thu, 01 Jan 2099 00:00:00 GMT", c.expires());
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());
        assertEquals("Strict", c.sameSite());
    }

    @Test
    public void testByteBufServerCookieDecoderCaseInsensitive() {
        DefaultCookie c = ServerCookieDecoder.decode(toBuf("k=v; SECURE; HTTPONLY; SAMESITE=lax; PATH=/; DOMAIN=foo.com; MAX-AGE=60"));
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());
        assertEquals("lax", c.sameSite());
        assertEquals("/", c.path());
        assertEquals("foo.com", c.domain());
        assertEquals(60L, c.maxAge());
    }

    @Test
    public void testByteBufServerCookieDecoderNullReturnsNull() {
        assertNull(ServerCookieDecoder.decode((ByteBuf) null));
    }

    @Test
    public void testByteBufServerCookieDecoderQuotedValue() {
        DefaultCookie c = ServerCookieDecoder.decode(toBuf("token=\"secret-value\""));
        assertEquals("secret-value", c.value());
    }

    // =========================================================================
    // ByteBuf-based CookieEncoder tests
    // =========================================================================

    @Test
    public void testByteBufCookieEncoderSingleCookie() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        CookieEncoder.encode(buf, new DefaultCookie("session", "abc"));
        assertEquals("session=abc", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufCookieEncoderMultipleCookies() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        Cookie c1 = new DefaultCookie("a", "1");
        Cookie c2 = new DefaultCookie("b", "2");
        Cookie c3 = new DefaultCookie("c", "3");
        CookieEncoder.encode(buf, c1, c2, c3);
        assertEquals("a=1; b=2; c=3", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufCookieEncoderCollection() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        List<Cookie> list = Arrays.asList(new DefaultCookie("x", "10"), new DefaultCookie("y", "20"));
        CookieEncoder.encode(buf, list);
        assertEquals("x=10; y=20", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufCookieEncoderOnlyNameValue() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        DefaultCookie c = new DefaultCookie("s", "v").setPath("/").setDomain("a.com").setHttpOnly(true);
        CookieEncoder.encode(buf, c);
        String encoded = bufToString(buf);
        assertEquals("s=v", encoded);
        assertFalse(encoded.contains("Path"));
        buf.free();
    }

    // =========================================================================
    // ByteBuf-based ServerCookieEncoder tests
    // =========================================================================

    @Test
    public void testByteBufServerCookieEncoderMinimal() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        ServerCookieEncoder.encode(buf, new DefaultCookie("k", "v"));
        assertEquals("k=v", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufServerCookieEncoderWithAllAttributes() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        DefaultCookie c = new DefaultCookie("session", "abc").setDomain("example.com").setPath("/").setMaxAge(3600).setExpires("Mon, 01 Jan 2099 00:00:00 GMT").setSecure(true).setHttpOnly(true).setSameSite("Lax");
        ServerCookieEncoder.encode(buf, c);
        assertEquals("session=abc; Domain=example.com; Path=/; Max-Age=3600; Expires=Mon, 01 Jan 2099 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufServerCookieEncoderSecureOnly() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        ServerCookieEncoder.encode(buf, new DefaultCookie("k", "v").setSecure(true));
        assertEquals("k=v; Secure", bufToString(buf));
        buf.free();
    }

    @Test
    public void testByteBufServerCookieEncoderMaxAgeZero() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        ServerCookieEncoder.encode(buf, new DefaultCookie("old", "gone").setMaxAge(0));
        assertTrue(bufToString(buf).contains("Max-Age=0"));
        buf.free();
    }

    // =========================================================================
    // ByteBuf Round-trip: encode to buf, then decode from buf
    // =========================================================================

    @Test
    public void testByteBufRoundTripRequestCookie() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        Cookie original1 = new DefaultCookie("lang", "en");
        Cookie original2 = new DefaultCookie("theme", "dark");
        CookieEncoder.encode(buf, original1, original2);
        buf.markWriter();

        List<Cookie> decoded = CookieDecoder.decode(buf);
        assertEquals(2, decoded.size());
        assertEquals("lang", decoded.get(0).name());
        assertEquals("en", decoded.get(0).value());
        assertEquals("theme", decoded.get(1).name());
        assertEquals("dark", decoded.get(1).value());
        buf.free();
    }

    @Test
    public void testByteBufRoundTripResponseCookie() {
        ByteBuf buf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer();
        DefaultCookie original = new DefaultCookie("session", "s3cr3t").setDomain("example.com").setPath("/").setMaxAge(86400).setSecure(true).setHttpOnly(true).setSameSite("Lax");
        ServerCookieEncoder.encode(buf, original);
        buf.markWriter();

        DefaultCookie decoded = ServerCookieDecoder.decode(buf);
        assertNotNull(decoded);
        assertEquals("session", decoded.name());
        assertEquals("s3cr3t", decoded.value());
        assertEquals("example.com", decoded.domain());
        assertEquals("/", decoded.path());
        assertEquals(86400L, decoded.maxAge());
        assertTrue(decoded.isSecure());
        assertTrue(decoded.isHttpOnly());
        assertEquals("Lax", decoded.sameSite());
        buf.free();
    }
}
