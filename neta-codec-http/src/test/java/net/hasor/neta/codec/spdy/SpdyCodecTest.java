package net.hasor.neta.codec.spdy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive tests for SPDY/3.1 codec (encoder + decoder) implementation.
 * Uses direct handler invocation for round-trip testing.
 */
public class SpdyCodecTest {

    // ========================= Mock & Helpers =========================

    private static ProtoContext mockContext() {
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            return null;
        });
    }

    // ========================= Inner Queue Helpers =========================

    private static class SimpleProtoRcvQueue<T> implements ProtoRcvQueue<T> {
        private final List<T> list = new ArrayList<>();

        public void add(T item) {
            list.add(item);
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int queueSize() {
            return list.size();
        }

        @Override
        public ProtoRcvQueue<T> rcvSubmit() {
            return this;
        }

        @Override
        public ProtoRcvQueue<T> rcvReset() {
            return this;
        }

        @Override
        public List<T> takeMessage(int cnt) {
            if (list.isEmpty())
                return Collections.emptyList();
            int take = Math.min(cnt, list.size());
            List<T> result = new ArrayList<>(list.subList(0, take));
            list.subList(0, take).clear();
            return result;
        }

        @Override
        public List<T> peekMessage(int cnt) {
            if (list.isEmpty())
                return Collections.emptyList();
            int take = Math.min(cnt, list.size());
            return new ArrayList<>(list.subList(0, take));
        }

        @Override
        public void skipMessage(int cnt) {
            int skip = Math.min(cnt, list.size());
            list.subList(0, skip).clear();
        }
    }

    private static class SimpleProtoSndQueue<T> implements ProtoSndQueue<T> {
        final List<T> list = new ArrayList<>();

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int slotSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean hasCommit() {
            return true;
        }

        @Override
        public ProtoSndQueue<T> sndSubmit() {
            return this;
        }

        @Override
        public ProtoSndQueue<T> sndReset() {
            return this;
        }

        @Override
        public int offerMessage(T[] offerList) {
            Collections.addAll(list, offerList);
            return offerList.length;
        }

        @Override
        public int offerMessage(List<T> offerList) {
            list.addAll(offerList);
            return offerList.size();
        }

        @Override
        public int offerMessage(ProtoRcvQueue<T> offerList) {
            int count = 0;
            while (offerList.hasMore()) {
                list.add(offerList.takeMessage());
                count++;
            }
            return count;
        }

        public int size() {
            return list.size();
        }

        public T poll() {
            return list.isEmpty() ? null : list.remove(0);
        }
    }

    // ========================= Round-Trip Helpers =========================

    /** Client → Server round-trip. */
    private List<HttpObject> clientToServer(HttpObject... messages) throws Throwable {
        SpdyFrameEncoder encoder = new SpdyFrameEncoder(false);
        SpdyFrameDecoder decoder = new SpdyFrameDecoder(true);

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Server → Client round-trip. */
    private List<HttpObject> serverToClient(HttpObject... messages) throws Throwable {
        SpdyFrameEncoder encoder = new SpdyFrameEncoder(true);
        SpdyFrameDecoder decoder = new SpdyFrameDecoder(false);

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    private <T> T findFirst(List<HttpObject> objects, Class<T> type) {
        for (HttpObject o : objects) {
            if (type.isInstance(o))
                return type.cast(o);
        }
        return null;
    }

    private <T> T findLast(List<HttpObject> objects, Class<T> type) {
        T last = null;
        for (HttpObject o : objects) {
            if (type.isInstance(o))
                last = type.cast(o);
        }
        return last;
    }

    // ========================= Round-Trip Request Tests =========================

    @Test
    public void testRoundTripGetRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, "/index.html");
        req.headers().add("host", "www.example.com");
        req.headers().add("accept", "text/html");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals("/index.html", received.uri());
        assertEquals(HttpMethod.GET, received.method());
    }

    @Test
    public void testRoundTripPostRequestWithBody() throws Throwable {
        String body = "{\"key\":\"value\",\"number\":42}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.POST, "/api/data", bodyBuf);
        req.headers().add("host", "api.example.com");
        req.headers().add("content-type", "application/json");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.POST, received.method());
        assertEquals("/api/data", received.uri());

        HttpContent lastContent = findLast(decoded, HttpContent.class);
        assertNotNull(lastContent);
        ByteBuf content = lastContent.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);
    }

    @Test
    public void testRoundTripResponse() throws Throwable {
        String body = "Hello, SPDY!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.SPDY_3_1, HttpStatus.OK, bodyBuf);
        resp.headers().add("content-type", "text/plain");

        List<HttpObject> decoded = serverToClient(resp);
        assertTrue(decoded.size() >= 1);

        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.OK, received.status());
    }

    @Test
    public void testRoundTripBidirectional() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, "/hello");
        req.headers().add("host", "localhost");
        List<HttpObject> serverSide = clientToServer(req);
        assertTrue(serverSide.size() >= 1);

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.SPDY_3_1, HttpStatus.OK);
        List<HttpObject> clientSide = serverToClient(resp);
        assertTrue(clientSide.size() >= 1);
    }

    // ========================= HTTP Method Tests =========================

    @Test
    public void testHeadRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.HEAD, "/status");
        req.headers().add("host", "check.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.HEAD, received.method());
    }

    @Test
    public void testPutRequest() throws Throwable {
        String body = "updated content";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.PUT, "/resource/1", bodyBuf);
        req.headers().add("host", "api.example.com");
        req.headers().add("content-type", "text/plain");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.PUT, received.method());
        assertEquals("/resource/1", received.uri());

        HttpContent lastContent = findLast(decoded, HttpContent.class);
        assertNotNull(lastContent);
        ByteBuf content = lastContent.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testDeleteRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.DELETE, "/resource/42");
        req.headers().add("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.DELETE, received.method());
        assertEquals("/resource/42", received.uri());
    }

    // ========================= Response Status Tests =========================

    @Test
    public void test404Response() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.SPDY_3_1, HttpStatus.NOT_FOUND);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.NOT_FOUND, received.status());
    }

    @Test
    public void test500ResponseWithBody() throws Throwable {
        String body = "Internal Server Error";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.SPDY_3_1, HttpStatus.INTERNAL_SERVER_ERROR, bodyBuf);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, received.status());
    }

    // ========================= Header Tests =========================

    @Test
    public void testMultipleHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, "/multi");
        req.headers().add("host", "example.com");
        req.headers().add("accept", "text/html");
        req.headers().add("accept-language", "en-US");
        req.headers().add("cache-control", "no-cache");
        req.headers().add("user-agent", "Neta/1.0");
        req.headers().add("x-custom-header", "custom-value");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals("text/html", received.headers().get("accept"));
        assertEquals("en-US", received.headers().get("accept-language"));
        assertEquals("no-cache", received.headers().get("cache-control"));
    }

    @Test
    public void testGetRequestNoBody() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, "/");
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.GET, received.method());
        assertEquals("/", received.uri());
    }

    // ========================= Boundary Tests =========================

    @Test
    public void testEmptyBody() throws Throwable {
        ByteBuf emptyBuf = ByteBufAllocator.DEFAULT.buffer(0);
        emptyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.POST, "/empty", emptyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);
    }

    @Test
    public void testLongUri() throws Throwable {
        StringBuilder uri = new StringBuilder("/path?");
        for (int i = 0; i < 200; i++) {
            if (i > 0)
                uri.append("&");
            uri.append("param").append(i).append("=value").append(i);
        }

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, uri.toString());
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri.toString(), received.uri());
    }

    @Test
    public void testSingleByteBody() throws Throwable {
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(1);
        bodyBuf.writeBytes(new byte[] { 0x42 }, 0, 1);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.POST, "/byte", bodyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);
    }

    // ========================= Stress Tests =========================

    @Test
    public void testLargeBody64KB() throws Throwable {
        byte[] bodyBytes = new byte[65536];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) (i % 256);
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.POST, "/large", bodyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() > 0);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);

        LastHttpContent lastContent = findLast(decoded, LastHttpContent.class);
        assertNotNull(lastContent);
        assertEquals(65536, lastContent.content().readableBytes());
    }

    @Test
    public void testMultipleSequentialRequests() throws Throwable {
        SpdyFrameEncoder encoder = new SpdyFrameEncoder(false);
        SpdyFrameDecoder decoder = new SpdyFrameDecoder(true);

        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();
        for (int i = 0; i < 20; i++) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.SPDY_3_1, HttpMethod.GET, "/page/" + i);
            req.headers().add("host", "localhost");
            encIn.add(req);
            encoder.onMessage(mockContext(), encIn, encOut);
            while (encOut.size() > 0)
                allEncOut.list.add(encOut.poll());
        }

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (allEncOut.size() > 0)
            decIn.add(allEncOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        int requestCount = 0;
        while (decOut.size() > 0) {
            if (decOut.poll() instanceof HttpRequest)
                requestCount++;
        }
        assertTrue("Expected 20 requests, got " + requestCount, requestCount >= 20);
    }

    // ========================= SpdyHeaderBlockCodec Unit Tests =========================

    @Test
    public void testSpdyHeaderBlockCodecRoundTrip() {
        HttpHeaders original = new HttpHeaders();
        original.add(":method", "GET");
        original.add(":path", "/");
        original.add(":version", "HTTP/1.1");
        original.add(":host", "example.com");
        original.add("accept", "text/html");
        original.add("user-agent", "test");

        byte[] encoded = SpdyHeaderBlockCodec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        HttpHeaders decoded = SpdyHeaderBlockCodec.decode(encoded, 0, encoded.length);
        assertEquals("GET", decoded.get(":method"));
        assertEquals("/", decoded.get(":path"));
        assertEquals("HTTP/1.1", decoded.get(":version"));
        assertEquals("example.com", decoded.get(":host"));
        assertEquals("text/html", decoded.get("accept"));
        assertEquals("test", decoded.get("user-agent"));
    }

    @Test
    public void testSpdyHeaderBlockCodecLargeValue() {
        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            largeValue.append("abcdefghij");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-large-header", largeValue.toString());

        byte[] encoded = SpdyHeaderBlockCodec.encode(headers);
        HttpHeaders decoded = SpdyHeaderBlockCodec.decode(encoded, 0, encoded.length);
        assertEquals(largeValue.toString(), decoded.get("x-large-header"));
    }

    @Test
    public void testSpdyHeaderBlockCodecEmptyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        byte[] encoded = SpdyHeaderBlockCodec.encode(headers);
        HttpHeaders decoded = SpdyHeaderBlockCodec.decode(encoded, 0, encoded.length);
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testSpdyHeaderBlockCodecManyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < 50; i++) {
            headers.add("x-header-" + i, "value-" + i);
        }

        byte[] encoded = SpdyHeaderBlockCodec.encode(headers);
        HttpHeaders decoded = SpdyHeaderBlockCodec.decode(encoded, 0, encoded.length);
        for (int i = 0; i < 50; i++) {
            assertEquals("value-" + i, decoded.get("x-header-" + i));
        }
    }

    @Test(expected = HttpProtocolException.class)
    public void testSpdyHeaderBlockCodecTruncated() {
        byte[] data = new byte[] { 0x00, 0x00 };
        SpdyHeaderBlockCodec.decode(data, 0, data.length);
    }

    // ========================= Constants Tests =========================

    @Test
    public void testSpdyFrameTypeNames() {
        assertEquals("SYN_STREAM", SpdyFrameType.name(SpdyFrameType.SYN_STREAM));
        assertEquals("SYN_REPLY", SpdyFrameType.name(SpdyFrameType.SYN_REPLY));
        assertEquals("RST_STREAM", SpdyFrameType.name(SpdyFrameType.RST_STREAM));
        assertEquals("SETTINGS", SpdyFrameType.name(SpdyFrameType.SETTINGS));
        assertEquals("PING", SpdyFrameType.name(SpdyFrameType.PING));
        assertEquals("GOAWAY", SpdyFrameType.name(SpdyFrameType.GOAWAY));
        assertEquals("HEADERS", SpdyFrameType.name(SpdyFrameType.HEADERS));
        assertEquals("WINDOW_UPDATE", SpdyFrameType.name(SpdyFrameType.WINDOW_UPDATE));
        assertTrue(SpdyFrameType.name(999).contains("UNKNOWN"));
    }

    @Test
    public void testSpdyFrameTypeValues() {
        assertEquals(1, SpdyFrameType.SYN_STREAM);
        assertEquals(2, SpdyFrameType.SYN_REPLY);
        assertEquals(3, SpdyFrameType.RST_STREAM);
        assertEquals(4, SpdyFrameType.SETTINGS);
        assertEquals(6, SpdyFrameType.PING);
        assertEquals(7, SpdyFrameType.GOAWAY);
        assertEquals(8, SpdyFrameType.HEADERS);
        assertEquals(9, SpdyFrameType.WINDOW_UPDATE);
    }

    @Test
    public void testSpdyFlags() {
        assertEquals(0x00, SpdyFlags.NONE);
        assertEquals(0x01, SpdyFlags.FLAG_FIN);
        assertEquals(0x02, SpdyFlags.FLAG_UNIDIRECTIONAL);

        assertTrue(SpdyFlags.fin(SpdyFlags.FLAG_FIN));
        assertFalse(SpdyFlags.fin(SpdyFlags.NONE));
        assertTrue(SpdyFlags.unidirectional(SpdyFlags.FLAG_UNIDIRECTIONAL));
        assertFalse(SpdyFlags.unidirectional(SpdyFlags.FLAG_FIN));

        int combined = SpdyFlags.FLAG_FIN | SpdyFlags.FLAG_UNIDIRECTIONAL;
        assertTrue(SpdyFlags.fin(combined));
        assertTrue(SpdyFlags.unidirectional(combined));
    }
}
