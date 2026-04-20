package net.hasor.neta.codec.http.h2;

import static org.junit.Assert.*;

import org.junit.Test;

import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * HTTP/2 support tests that are not tied to a specific encoder/decoder handler.
 */
public class Http2HpackTest {
    @Test
    public void testHpackEncodeDecodeRoundTrip() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        DefaultHttpHeaders original = new DefaultHttpHeaders();
        original.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
        original.addHeader(HttpHeaderNames.PSEUDO_PATH, "/");
        original.addHeader(HttpHeaderNames.PSEUDO_SCHEME, "https");
        original.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
        original.addHeader(HttpHeaderNames.ACCEPT, "text/html");
        original.addHeader(HttpHeaderNames.USER_AGENT, "test");

        byte[] encoded = encoder.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("GET", decoded.getString(HttpHeaderNames.PSEUDO_METHOD));
        assertEquals("/", decoded.getString(HttpHeaderNames.PSEUDO_PATH));
        assertEquals("https", decoded.getString(HttpHeaderNames.PSEUDO_SCHEME));
        assertEquals("example.com", decoded.getString(HttpHeaderNames.PSEUDO_AUTHORITY));
        assertEquals("text/html", decoded.getString(HttpHeaderNames.ACCEPT));
        assertEquals("test", decoded.getString(HttpHeaderNames.USER_AGENT));
    }

    @Test
    public void testHpackStaticTableIndexing() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
        headers.addHeader(HttpHeaderNames.PSEUDO_PATH, "/");
        headers.addHeader(HttpHeaderNames.PSEUDO_SCHEME, "https");

        byte[] encoded = encoder.encode(headers);
        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);

        assertEquals("GET", decoded.getString(HttpHeaderNames.PSEUDO_METHOD));
        assertEquals("/", decoded.getString(HttpHeaderNames.PSEUDO_PATH));
        assertEquals("https", decoded.getString(HttpHeaderNames.PSEUDO_SCHEME));
    }

    @Test
    public void testHpackLargeHeaderValue() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeValue.append("abcdefghij");
        }

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("x-large-header", largeValue.toString());

        byte[] encoded = encoder.encode(headers);
        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals(largeValue.toString(), decoded.getString("x-large-header"));
    }

    @Test
    public void testHpackEmptyHeaders() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        byte[] encoded = encoder.encode(headers);
        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals(0, decoded.headerSize());
    }

    @Test
    public void testHpackDynamicTableEviction() {
        HpackEncoder encoder = new HpackEncoder(64);
        HpackDecoder decoder = new HpackDecoder(64, 8192);

        DefaultHttpHeaders h1 = new DefaultHttpHeaders();
        h1.addHeader("x-key-1", "value-one-that-is-long");
        byte[] e1 = encoder.encode(h1);
        decoder.decode(e1, 0, e1.length);

        DefaultHttpHeaders h2 = new DefaultHttpHeaders();
        h2.addHeader("x-key-2", "value-two-that-is-long");
        byte[] e2 = encoder.encode(h2);
        DefaultHttpHeaders d2 = decoder.decode(e2, 0, e2.length);
        assertEquals("value-two-that-is-long", d2.getString("x-key-2"));
    }

    @Test
    public void testHpackSequentialEncodingSharesContext() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        DefaultHttpHeaders h1 = new DefaultHttpHeaders();
        h1.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
        h1.addHeader(HttpHeaderNames.PSEUDO_PATH, "/page1");
        h1.addHeader(HttpHeaderNames.PSEUDO_SCHEME, "https");
        h1.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
        byte[] e1 = encoder.encode(h1);
        HttpHeaders d1 = decoder.decode(e1, 0, e1.length);
        assertEquals("/page1", d1.getString(HttpHeaderNames.PSEUDO_PATH));

        DefaultHttpHeaders h2 = new DefaultHttpHeaders();
        h2.addHeader(HttpHeaderNames.PSEUDO_METHOD, "GET");
        h2.addHeader(HttpHeaderNames.PSEUDO_PATH, "/page2");
        h2.addHeader(HttpHeaderNames.PSEUDO_SCHEME, "https");
        h2.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, "example.com");
        byte[] e2 = encoder.encode(h2);
        HttpHeaders d2 = decoder.decode(e2, 0, e2.length);
        assertEquals("/page2", d2.getString(HttpHeaderNames.PSEUDO_PATH));
        assertTrue(e2.length <= e1.length);
    }

    @Test
    public void testHpackManyUniqueHeaders() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        for (int i = 0; i < 100; i++) {
            headers.addHeader("x-unique-" + i, "value-" + i + "-with-some-extra-data");
        }

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        for (int i = 0; i < 100; i++) {
            assertEquals("value-" + i + "-with-some-extra-data", decoded.getString("x-unique-" + i));
        }
    }

    @Test
    public void testHpackHeaderWithSpecialChars() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("x-special", "key=value; path=/; domain=.example.com");
        headers.addHeader(HttpHeaderNames.COOKIE, "session=abc123; lang=en-US");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("key=value; path=/; domain=.example.com", decoded.getString("x-special"));
        assertEquals("session=abc123; lang=en-US", decoded.getString(HttpHeaderNames.COOKIE));
    }

    @Test
    public void testHpackSmallDynamicTable() {
        HpackEncoder encoder = new HpackEncoder(32);
        HpackDecoder decoder = new HpackDecoder(32, 65536);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("x-key", "value");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("value", decoded.getString("x-key"));
    }

    @Test
    public void testHpackInvalidIndexedFieldUsesCompressionException() {
        HpackDecoder decoder = new HpackDecoder(4096, 65536);
        try {
            decoder.decode(new byte[] { (byte) 0x80 }, 0, 1);
            fail("expected compression exception");
        } catch (HpackDecodingException e) {
            assertTrue(e.getMessage().contains("index 0"));
        }
    }

    @Test
    public void testHttp2FrameTypeNames() {
        assertEquals("DATA", Http2FrameType.name(0x00));
        assertEquals("HEADERS", Http2FrameType.name(0x01));
        assertEquals("PRIORITY", Http2FrameType.name(0x02));
        assertEquals("RST_STREAM", Http2FrameType.name(0x03));
        assertEquals("SETTINGS", Http2FrameType.name(0x04));
        assertEquals("PUSH_PROMISE", Http2FrameType.name(0x05));
        assertEquals("PING", Http2FrameType.name(0x06));
        assertEquals("GOAWAY", Http2FrameType.name(0x07));
        assertEquals("WINDOW_UPDATE", Http2FrameType.name(0x08));
        assertEquals("CONTINUATION", Http2FrameType.name(0x09));
        assertEquals("PREFACE", Http2FrameType.name(0xFF));
    }

    @Test
    public void testHttp2Flags() {
        assertTrue(Http2Flags.endStream(Http2Flags.END_STREAM));
        assertFalse(Http2Flags.endStream(Http2Flags.NONE));
        assertTrue(Http2Flags.endHeaders(Http2Flags.END_HEADERS));
        assertTrue(Http2Flags.padded(Http2Flags.PADDED));
        assertTrue(Http2Flags.priority(Http2Flags.PRIORITY));
        assertTrue(Http2Flags.ack(Http2Flags.ACK));

        int combined = Http2Flags.END_STREAM | Http2Flags.END_HEADERS;
        assertTrue(Http2Flags.endStream(combined));
        assertTrue(Http2Flags.endHeaders(combined));
        assertFalse(Http2Flags.padded(combined));
    }

    @Test
    public void testHttp2StreamStates() {
        Http2StreamState[] states = Http2StreamState.values();
        assertEquals(7, states.length);
        assertEquals(Http2StreamState.IDLE, Http2StreamState.valueOf("IDLE"));
        assertEquals(Http2StreamState.OPEN, Http2StreamState.valueOf("OPEN"));
        assertEquals(Http2StreamState.CLOSED, Http2StreamState.valueOf("CLOSED"));
        assertEquals(Http2StreamState.HALF_CLOSED_LOCAL, Http2StreamState.valueOf("HALF_CLOSED_LOCAL"));
        assertEquals(Http2StreamState.HALF_CLOSED_REMOTE, Http2StreamState.valueOf("HALF_CLOSED_REMOTE"));
    }

    @Test
    public void testHttp2ErrorCodeValues() {
        assertEquals(0x00L, Http2ErrorCode.NO_ERROR);
        assertEquals(0x01L, Http2ErrorCode.PROTOCOL_ERROR);
        assertEquals(0x02L, Http2ErrorCode.INTERNAL_ERROR);
        assertEquals(0x03L, Http2ErrorCode.FLOW_CONTROL_ERROR);
        assertEquals(0x06L, Http2ErrorCode.FRAME_SIZE_ERROR);
        assertEquals(0x08L, Http2ErrorCode.CANCEL);
        assertEquals(0x09L, Http2ErrorCode.COMPRESSION_ERROR);
        assertEquals(0x0dL, Http2ErrorCode.HTTP_1_1_REQUIRED);
    }

    @Test
    public void testHttp2SettingsDefaults() {
        Http2Settings settings = new Http2Settings();
        assertEquals(4096L, settings.headerTableSize());
        assertEquals(65535, settings.initialWindowSize());
        assertEquals(16384, settings.maxFrameSize());
    }

    @Test
    public void testHttp2SettingsCustomValues() {
        Http2Settings settings = new Http2Settings();
        settings.headerTableSize(8192);
        settings.initialWindowSize(131072);
        settings.maxFrameSize(32768);

        assertEquals(8192L, settings.headerTableSize());
        assertEquals(131072, settings.initialWindowSize());
        assertEquals(32768, settings.maxFrameSize());
    }

    @Test
    public void testHttp2SettingsMaxConcurrentStreams() {
        Http2Settings settings = new Http2Settings();
        settings.maxConcurrentStreams(256);
        assertEquals(256L, settings.maxConcurrentStreams());
    }
}
