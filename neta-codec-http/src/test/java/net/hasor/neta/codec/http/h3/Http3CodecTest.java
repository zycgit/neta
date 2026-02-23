package net.hasor.neta.codec.http.h3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.quic.QuicVarInt;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive tests for HTTP/3 codec (encoder + decoder) implementation.
 * Tests round-trip encoding/decoding, QPACK header compression, stream management,
 * error handling, and boundary conditions.
 */
public class Http3CodecTest {

    // ========================= Mock & Helpers =========================

    private static ProtoContext mockContext() {
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            return null;
        });
    }

    private static ByteBuf toByteBuf(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return buf;
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

    /** Encodes HttpObject via client encoder, then decodes via server decoder. Returns decoded objects. */
    private List<HttpObject> clientToServer(HttpObject... messages) throws Throwable {
        Http3FrameEncoder encoder = new Http3FrameEncoder(false); // client
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);  // server

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        encoder.onMessage(mockContext(), encIn, encOut);

        // Concatenate all encoder output ByteBufs into one (simulates single-stream delivery)
        ByteBuf combined = combineAll(encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(combined);
        decoder.setNextStreamMeta(0, true); // stream 0, fin=true (complete request)
        decoder.onMessage(mockContext(), decIn, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Encodes HttpObject via server encoder, then decodes via client decoder. Returns decoded objects. */
    private List<HttpObject> serverToClient(HttpObject... messages) throws Throwable {
        Http3FrameEncoder encoder = new Http3FrameEncoder(true);  // server
        Http3FrameDecoder decoder = new Http3FrameDecoder(false); // client

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        encoder.onMessage(mockContext(), encIn, encOut);

        // Concatenate all encoder output ByteBufs into one (simulates single-stream delivery)
        ByteBuf combined = combineAll(encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(combined);
        decoder.setNextStreamMeta(0, true); // stream 0, fin=true (complete response)
        decoder.onMessage(mockContext(), decIn, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Concatenates all ByteBufs from a queue into a single ByteBuf. */
    private static ByteBuf combineAll(SimpleProtoSndQueue<ByteBuf> queue) {
        int total = 0;
        List<ByteBuf> bufs = new ArrayList<>();
        while (queue.size() > 0) {
            ByteBuf b = queue.poll();
            total += b.readableBytes();
            bufs.add(b);
        }
        ByteBuf combined = ByteBufAllocator.DEFAULT.buffer(total);
        for (ByteBuf b : bufs) {
            int len = b.readableBytes();
            byte[] tmp = new byte[len];
            b.getBytes(0, tmp, 0, len);
            combined.writeBytes(tmp, 0, len);
        }
        combined.markWriter();
        return combined;
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

    private <T> List<T> findAll(List<HttpObject> objects, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (HttpObject o : objects) {
            if (type.isInstance(o))
                result.add(type.cast(o));
        }
        return result;
    }

    // ========================= Round-Trip Request Tests =========================

    @Test
    public void testRoundTripGetRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/index.html");
        req.headers().add("host", "www.example.com");
        req.headers().add("accept", "text/html");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue("Should decode at least 1 object", decoded.size() >= 1);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull("Should decode an HttpRequest", received);
        assertEquals("/index.html", received.uri());
        assertEquals(HttpMethod.GET, received.method());
        assertEquals(HttpVersion.HTTP_3_0, received.protocolVersion());
    }

    @Test
    public void testRoundTripPostRequestWithBody() throws Throwable {
        String body = "{\"key\":\"value\",\"number\":42}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/api/data", bodyBuf);
        req.headers().add("host", "api.example.com");
        req.headers().add("content-type", "application/json");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue("Should decode at least 1 object", decoded.size() >= 1);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.POST, received.method());
        assertEquals("/api/data", received.uri());

        HttpContent lastContent = findLast(decoded, HttpContent.class);
        assertNotNull("POST body should be decoded", lastContent);
        ByteBuf content = lastContent.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);
    }

    @Test
    public void testRoundTripResponse200() throws Throwable {
        String body = "Hello, HTTP/3!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, bodyBuf);
        resp.headers().add("content-type", "text/plain");

        List<HttpObject> decoded = serverToClient(resp);
        assertTrue("Should decode at least 1 object", decoded.size() >= 1);

        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.OK, received.status());
    }

    @Test
    public void testRoundTripBidirectional() throws Throwable {
        // Client → Server
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/hello");
        req.headers().add("host", "localhost");
        List<HttpObject> serverSide = clientToServer(req);
        assertTrue(serverSide.size() >= 1);

        // Server → Client
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
        List<HttpObject> clientSide = serverToClient(resp);
        assertTrue(clientSide.size() >= 1);
    }

    // ========================= HTTP Method Tests =========================

    @Test
    public void testHeadRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.HEAD, "/status");
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

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.PUT, "/resource/1", bodyBuf);
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
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.DELETE, "/resource/42");
        req.headers().add("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.DELETE, received.method());
        assertEquals("/resource/42", received.uri());
    }

    @Test
    public void testOptionsRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.OPTIONS, "*");
        req.headers().add("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.OPTIONS, received.method());
        assertEquals("*", received.uri());
    }

    @Test
    public void testConnectMethod() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.CONNECT, "proxy.example.com:443");
        req.headers().add("host", "proxy.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.CONNECT, received.method());
    }

    @Test
    public void testPatchRequest() throws Throwable {
        String body = "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"new\"}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.PATCH, "/resource/1", bodyBuf);
        req.headers().add("host", "api.example.com");
        req.headers().add("content-type", "application/json-patch+json");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.PATCH, received.method());
    }

    // ========================= Response Status Tests =========================

    @Test
    public void test404Response() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NOT_FOUND);
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

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.INTERNAL_SERVER_ERROR, bodyBuf);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, received.status());
    }

    @Test
    public void test301Redirect() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.MOVED_PERMANENTLY);
        resp.headers().add("location", "https://new.example.com/path");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.MOVED_PERMANENTLY, received.status());
        assertEquals("https://new.example.com/path", received.headers().get("location"));
    }

    @Test
    public void test204NoContent() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NO_CONTENT);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.NO_CONTENT, received.status());
    }

    @Test
    public void test304NotModified() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NOT_MODIFIED);
        resp.headers().add("etag", "\"abc123\"");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.NOT_MODIFIED, received.status());
        assertEquals("\"abc123\"", received.headers().get("etag"));
    }

    @Test
    public void test503ServiceUnavailable() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.SERVICE_UNAVAILABLE);
        resp.headers().add("retry-after", "120");

        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, received.status());
    }

    // ========================= Header Tests =========================

    @Test
    public void testMultipleHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/multi");
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
    public void testManyHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/many-headers");
        req.headers().add("host", "localhost");
        for (int i = 0; i < 50; i++) {
            req.headers().add("x-header-" + i, "value-" + i);
        }

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
    }

    @Test
    public void testHeaderWithEmptyValue() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/empty-header");
        req.headers().add("host", "localhost");
        req.headers().add("x-empty", "");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals("", received.headers().get("x-empty"));
    }

    @Test
    public void testResponseHeaders() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
        resp.headers().add("content-type", "application/json");
        resp.headers().add("x-request-id", "req-123-abc");
        resp.headers().add("cache-control", "no-store");

        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals("application/json", received.headers().get("content-type"));
        assertEquals("req-123-abc", received.headers().get("x-request-id"));
        assertEquals("no-store", received.headers().get("cache-control"));
    }

    // ========================= Boundary Tests =========================

    @Test
    public void testEmptyBody() throws Throwable {
        ByteBuf emptyBuf = ByteBufAllocator.DEFAULT.buffer(0);
        emptyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/empty", emptyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue("Should decode at least 1 object", decoded.size() >= 1);
    }

    @Test
    public void testLongUri() throws Throwable {
        StringBuilder uri = new StringBuilder("/path?");
        for (int i = 0; i < 200; i++) {
            if (i > 0)
                uri.append("&");
            uri.append("param").append(i).append("=value").append(i);
        }

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, uri.toString());
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri.toString(), received.uri());
    }

    @Test
    public void testUriWithSpecialCharacters() throws Throwable {
        String uri = "/path/to/resource?q=hello%20world&lang=en&special=%E4%B8%AD%E6%96%87";
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, uri);
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri, received.uri());
    }

    @Test
    public void testSingleByteBody() throws Throwable {
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(1);
        bodyBuf.writeBytes(new byte[] { 0x42 }, 0, 1);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/byte", bodyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);
    }

    @Test
    public void testGetRequestNoBody() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/");
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.GET, received.method());
        assertEquals("/", received.uri());
    }

    // ========================= Large Body Tests =========================

    @Test
    public void testLargeBody8KB() throws Throwable {
        byte[] bodyBytes = new byte[8192];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) (i % 256);
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/large", bodyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() > 0);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);

        LastHttpContent lastContent = findLast(decoded, LastHttpContent.class);
        assertNotNull(lastContent);
        assertEquals(8192, lastContent.content().readableBytes());
    }

    @Test
    public void testLargeResponseBody() throws Throwable {
        byte[] bodyBytes = new byte[16384];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) ('A' + (i % 26));
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, bodyBuf);
        resp.headers().add("content-type", "application/octet-stream");

        List<HttpObject> decoded = serverToClient(resp);
        assertTrue(decoded.size() > 0);

        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.OK, received.status());
    }

    // ========================= Multiple Requests (Stream Multiplexing) =========================

    @Test
    public void testMultipleSequentialRequests() throws Throwable {
        Http3FrameEncoder encoder = new Http3FrameEncoder(false);
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);

        int requestCount = 0;
        for (int i = 0; i < 10; i++) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/page/" + i);
            req.headers().add("host", "localhost");
            encIn.add(req);
            encoder.onMessage(mockContext(), encIn, encOut);

            ByteBuf combined = combineAll(encOut);

            SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
            decIn.add(combined);
            decoder.setNextStreamMeta(i * 4, true); // each request on its own stream
            decoder.onMessage(mockContext(), decIn, decOut);

            while (decOut.size() > 0) {
                if (decOut.poll() instanceof HttpRequest)
                    requestCount++;
            }
        }
        assertTrue("Expected 10 requests, got " + requestCount, requestCount >= 10);
    }

    @Test
    public void testMultipleResponsesOnDifferentStreams() throws Throwable {
        Http3FrameEncoder encoder = new Http3FrameEncoder(true);
        Http3FrameDecoder decoder = new Http3FrameDecoder(false);

        HttpStatus[] statuses = { HttpStatus.OK, HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NO_CONTENT };
        int responseCount = 0;
        long streamId = 0;
        for (HttpStatus status : statuses) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, status);
            encIn.add(resp);
            encoder.setResponseStreamId(streamId);
            encoder.onMessage(mockContext(), encIn, encOut);

            ByteBuf combined = combineAll(encOut);

            SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
            SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
            decIn.add(combined);
            decoder.setNextStreamMeta(streamId, true);
            decoder.onMessage(mockContext(), decIn, decOut);

            while (decOut.size() > 0) {
                if (decOut.poll() instanceof HttpResponse)
                    responseCount++;
            }
            streamId += 4;
        }
        assertTrue("Expected 4 responses, got " + responseCount, responseCount >= 4);
    }

    // ========================= QPACK Encoder/Decoder Unit Tests =========================

    @Test
    public void testQpackEncodeDecodeRoundTrip() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        HttpHeaders original = new HttpHeaders();
        original.add(":method", "GET");
        original.add(":path", "/");
        original.add(":scheme", "https");
        original.add(":authority", "example.com");
        original.add("accept", "text/html");
        original.add("user-agent", "test");

        byte[] encoded = encoder.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("GET", decoded.get(":method"));
        assertEquals("/", decoded.get(":path"));
        assertEquals("https", decoded.get(":scheme"));
        assertEquals("example.com", decoded.get(":authority"));
        assertEquals("text/html", decoded.get("accept"));
        assertEquals("test", decoded.get("user-agent"));
    }

    @Test
    public void testQpackStaticTableIndexedEncoding() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/");
        headers.add(":scheme", "https");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);

        assertEquals("GET", decoded.get(":method"));
        assertEquals("/", decoded.get(":path"));
        assertEquals("https", decoded.get(":scheme"));
    }

    @Test
    public void testQpackLargeHeaderValue() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            largeValue.append("abcdefghij");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-large-header", largeValue.toString());

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals(largeValue.toString(), decoded.get("x-large-header"));
    }

    @Test
    public void testQpackEmptyHeaders() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        HttpHeaders headers = new HttpHeaders();
        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testQpackMultipleHeadersWithSameName() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-multi", "value1");
        headers.add("x-multi", "value2");
        headers.add("x-multi", "value3");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        List<String> values = decoded.getAll("x-multi");
        assertEquals(3, values.size());
        assertTrue(values.contains("value1"));
        assertTrue(values.contains("value2"));
        assertTrue(values.contains("value3"));
    }

    @Test
    public void testQpackPseudoHeaders() {
        QpackEncoder encoder = new QpackEncoder(4096, false);
        QpackDecoder decoder = new QpackDecoder(4096, 65536);

        // Response pseudo-headers
        HttpHeaders headers = new HttpHeaders();
        headers.add(":status", "200");
        headers.add("content-type", "text/plain");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("200", decoded.get(":status"));
        assertEquals("text/plain", decoded.get("content-type"));
    }

    @Test
    public void testQpackStaticTableLookup() {
        // Verify static table entries for common values
        assertEquals(17, QpackStaticTable.findIndex(":method", "GET"));
        assertEquals(20, QpackStaticTable.findIndex(":method", "POST"));
        assertEquals(1, QpackStaticTable.findIndex(":path", "/"));
        assertEquals(23, QpackStaticTable.findIndex(":scheme", "https"));
        assertEquals(25, QpackStaticTable.findIndex(":status", "200"));
        assertEquals(27, QpackStaticTable.findIndex(":status", "404"));
    }

    @Test
    public void testQpackStaticTableNameLookup() {
        assertTrue(QpackStaticTable.findNameIndex(":method") >= 0);
        assertTrue(QpackStaticTable.findNameIndex(":path") >= 0);
        assertTrue(QpackStaticTable.findNameIndex(":scheme") >= 0);
        assertTrue(QpackStaticTable.findNameIndex(":status") >= 0);
        assertTrue(QpackStaticTable.findNameIndex("content-type") >= 0);
        assertEquals(-1, QpackStaticTable.findNameIndex("x-nonexistent"));
    }

    // ========================= Http3 Frame Types and Constants =========================

    @Test
    public void testHttp3FrameTypeNames() {
        assertEquals("DATA", Http3FrameType.name(0x00));
        assertEquals("HEADERS", Http3FrameType.name(0x01));
        assertEquals("CANCEL_PUSH", Http3FrameType.name(0x03));
        assertEquals("SETTINGS", Http3FrameType.name(0x04));
        assertEquals("PUSH_PROMISE", Http3FrameType.name(0x05));
        assertEquals("GOAWAY", Http3FrameType.name(0x07));
        assertEquals("MAX_PUSH_ID", Http3FrameType.name(0x0d));
        assertTrue(Http3FrameType.name(0xFF).contains("UNKNOWN"));
    }

    @Test
    public void testHttp3FrameTypeReserved() {
        // Reserved types: 0x1f * N + 0x21
        assertTrue(Http3FrameType.isReserved(0x21));   // N=0
        assertTrue(Http3FrameType.isReserved(0x40));   // N=1
        assertTrue(Http3FrameType.isReserved(0x5F));   // N=2
        assertFalse(Http3FrameType.isReserved(0x00));  // DATA
        assertFalse(Http3FrameType.isReserved(0x01));  // HEADERS
        assertFalse(Http3FrameType.isReserved(0x04));  // SETTINGS

        // Reserved frame names
        assertTrue(Http3FrameType.name(0x21).contains("RESERVED"));
    }

    @Test
    public void testHttp3ErrorCodeValues() {
        assertEquals(0x0100L, Http3ErrorCode.H3_NO_ERROR);
        assertEquals(0x0101L, Http3ErrorCode.H3_GENERAL_PROTOCOL_ERROR);
        assertEquals(0x0102L, Http3ErrorCode.H3_INTERNAL_ERROR);
        assertEquals(0x0103L, Http3ErrorCode.H3_STREAM_CREATION_ERROR);
        assertEquals(0x0104L, Http3ErrorCode.H3_CLOSED_CRITICAL_STREAM);
        assertEquals(0x0105L, Http3ErrorCode.H3_FRAME_UNEXPECTED);
        assertEquals(0x0106L, Http3ErrorCode.H3_FRAME_ERROR);
        assertEquals(0x0107L, Http3ErrorCode.H3_EXCESSIVE_LOAD);
        assertEquals(0x0108L, Http3ErrorCode.H3_ID_ERROR);
        assertEquals(0x0109L, Http3ErrorCode.H3_SETTINGS_ERROR);
        assertEquals(0x010aL, Http3ErrorCode.H3_MISSING_SETTINGS);
        assertEquals(0x010bL, Http3ErrorCode.H3_REQUEST_REJECTED);
        assertEquals(0x010cL, Http3ErrorCode.H3_REQUEST_CANCELLED);
        assertEquals(0x010dL, Http3ErrorCode.H3_REQUEST_INCOMPLETE);
        assertEquals(0x010eL, Http3ErrorCode.H3_MESSAGE_ERROR);
        assertEquals(0x010fL, Http3ErrorCode.H3_CONNECT_ERROR);
        assertEquals(0x0110L, Http3ErrorCode.H3_VERSION_FALLBACK);
    }

    @Test
    public void testHttp3ErrorCodeQpack() {
        assertEquals(0x0200L, Http3ErrorCode.QPACK_DECOMPRESSION_FAILED);
        assertEquals(0x0201L, Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR);
        assertEquals(0x0202L, Http3ErrorCode.QPACK_DECODER_STREAM_ERROR);
    }

    @Test
    public void testHttp3ErrorCodeNames() {
        assertEquals("H3_NO_ERROR", Http3ErrorCode.name(0x0100L));
        assertEquals("H3_FRAME_ERROR", Http3ErrorCode.name(0x0106L));
        assertEquals("QPACK_DECOMPRESSION_FAILED", Http3ErrorCode.name(0x0200L));
        assertTrue(Http3ErrorCode.name(0xFFFFL).contains("UNKNOWN"));
    }

    @Test
    public void testHttp3StreamStates() {
        Http3StreamState[] states = Http3StreamState.values();
        assertEquals(4, states.length);
        assertEquals(Http3StreamState.IDLE, Http3StreamState.valueOf("IDLE"));
        assertEquals(Http3StreamState.OPEN, Http3StreamState.valueOf("OPEN"));
        assertEquals(Http3StreamState.HALF_CLOSED, Http3StreamState.valueOf("HALF_CLOSED"));
        assertEquals(Http3StreamState.CLOSED, Http3StreamState.valueOf("CLOSED"));
    }

    @Test
    public void testHttp3SettingsDefaults() {
        Http3Settings settings = new Http3Settings();
        assertEquals(0L, settings.qpackMaxTableCapacity());
        assertEquals(Long.MAX_VALUE, settings.maxFieldSectionSize());
        assertEquals(0L, settings.qpackBlockedStreams());
        assertFalse(settings.enableConnectProtocol());
    }

    @Test
    public void testHttp3SettingsApply() {
        Http3Settings settings = new Http3Settings();
        settings.applySetting(Http3Settings.SETTINGS_QPACK_MAX_TABLE_CAPACITY, 8192);
        settings.applySetting(Http3Settings.SETTINGS_MAX_FIELD_SECTION_SIZE, 32768);
        settings.applySetting(Http3Settings.SETTINGS_QPACK_BLOCKED_STREAMS, 100);
        settings.applySetting(Http3Settings.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);

        assertEquals(8192L, settings.qpackMaxTableCapacity());
        assertEquals(32768L, settings.maxFieldSectionSize());
        assertEquals(100L, settings.qpackBlockedStreams());
        assertTrue(settings.enableConnectProtocol());
    }

    @Test
    public void testHttp3SettingsCopy() {
        Http3Settings original = new Http3Settings();
        original.qpackMaxTableCapacity(4096);
        original.maxFieldSectionSize(16384);

        Http3Settings copy = new Http3Settings(original);
        assertEquals(4096L, copy.qpackMaxTableCapacity());
        assertEquals(16384L, copy.maxFieldSectionSize());
    }

    @Test
    public void testHttp3SettingsReserved() {
        // Reserved settings: 0x1f * N + 0x21
        assertTrue(Http3Settings.isReservedSetting(0x21));
        assertTrue(Http3Settings.isReservedSetting(0x40));
        assertFalse(Http3Settings.isReservedSetting(0x01));
        assertFalse(Http3Settings.isReservedSetting(0x06));
    }

    // ========================= Http3Stream Tests =========================

    @Test
    public void testHttp3StreamLifecycle() {
        Http3Stream stream = new Http3Stream(1);
        assertEquals(1L, stream.streamId());
        assertEquals(Http3StreamState.IDLE, stream.state());
        assertFalse(stream.headersReceived());
        assertFalse(stream.trailersReceived());

        stream.state(Http3StreamState.OPEN);
        assertEquals(Http3StreamState.OPEN, stream.state());

        stream.markHeadersReceived();
        assertTrue(stream.headersReceived());

        stream.markTrailersReceived();
        assertTrue(stream.trailersReceived());

        stream.state(Http3StreamState.HALF_CLOSED);
        assertEquals(Http3StreamState.HALF_CLOSED, stream.state());
    }

    @Test
    public void testHttp3StreamHeaderBlockAccumulation() {
        Http3Stream stream = new Http3Stream(3);
        assertNull(stream.accumulatedHeaderBlock());

        stream.appendHeaderBlock(new byte[] { 0x01, 0x02 });
        assertArrayEquals(new byte[] { 0x01, 0x02 }, stream.accumulatedHeaderBlock());

        stream.appendHeaderBlock(new byte[] { 0x03, 0x04 });
        assertArrayEquals(new byte[] { 0x01, 0x02, 0x03, 0x04 }, stream.accumulatedHeaderBlock());

        stream.clearHeaderBlock();
        assertNull(stream.accumulatedHeaderBlock());
    }

    @Test
    public void testHttp3StreamRelease() {
        Http3Stream stream = new Http3Stream(5);
        stream.appendHeaderBlock(new byte[] { 0x01 });
        stream.state(Http3StreamState.OPEN);

        stream.release();
        assertNull(stream.accumulatedHeaderBlock());
        assertEquals(Http3StreamState.CLOSED, stream.state());
    }

    @Test
    public void testHttp3StreamToString() {
        Http3Stream stream = new Http3Stream(7);
        stream.state(Http3StreamState.OPEN);
        String str = stream.toString();
        assertTrue(str.contains("7"));
        assertTrue(str.contains("OPEN"));
    }

    // ========================= Raw Frame Construction Tests =========================

    @Test
    public void testSettingsFrameParsing() throws Throwable {
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);

        // Construct a control stream (streamId with bit1 set): streamId=2 (unidirectional)
        // Control stream type = 0x00 + SETTINGS frame
        byte[] controlStreamType = QuicVarInt.encode(0x00); // control stream
        byte[] settingsType = QuicVarInt.encode(Http3FrameType.SETTINGS);
        // SETTINGS payload: QPACK_MAX_TABLE_CAPACITY=4096, MAX_FIELD_SECTION_SIZE=8192
        byte[] settingId1 = QuicVarInt.encode(Http3Settings.SETTINGS_QPACK_MAX_TABLE_CAPACITY);
        byte[] settingVal1 = QuicVarInt.encode(4096);
        byte[] settingId2 = QuicVarInt.encode(Http3Settings.SETTINGS_MAX_FIELD_SECTION_SIZE);
        byte[] settingVal2 = QuicVarInt.encode(8192);
        byte[] settingsPayload = concat(settingId1, settingVal1, settingId2, settingVal2);
        byte[] settingsLength = QuicVarInt.encode(settingsPayload.length);

        byte[] streamData = concat(controlStreamType, settingsType, settingsLength, settingsPayload);
        ByteBuf buf = toByteBuf(streamData);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(buf);
        decoder.setNextStreamMeta(2, false); // unidirectional streamId=2, not fin
        decoder.onMessage(mockContext(), decIn, decOut);

        // Settings frame should be consumed internally, no HttpObject output
        assertEquals(0, decOut.size());

        // Verify settings were applied
        Http3Context ctx = decoder.createContext();
        assertTrue("Settings should mark as received", ctx.isReady());
    }

    @Test
    public void testDataTooSmallSkipped() throws Throwable {
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);

        // Empty ByteBuf should be skipped gracefully
        ByteBuf emptyBuf = ByteBufAllocator.DEFAULT.buffer(0);
        emptyBuf.markWriter();

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(emptyBuf);
        decoder.onMessage(mockContext(), decIn, decOut);
        assertEquals(0, decOut.size());

        // Null-readable ByteBuf should also be skipped
        ByteBuf nullReadable = ByteBufAllocator.DEFAULT.buffer(5);
        // don't write anything, so readableBytes == 0
        nullReadable.markWriter();
        decIn.add(nullReadable);
        decoder.onMessage(mockContext(), decIn, decOut);
        assertEquals(0, decOut.size());
    }

    // ========================= QuicVarInt Encoding/Decoding Tests =========================

    @Test
    public void testQuicVarIntSmallValues() {
        // 1-byte encoding: 0-63
        for (int i = 0; i <= 63; i++) {
            byte[] encoded = QuicVarInt.encode(i);
            assertEquals(1, encoded.length);
            long[] decoded = QuicVarInt.decode(encoded, 0);
            assertEquals(i, decoded[0]);
            assertEquals(1L, decoded[1]);
        }
    }

    @Test
    public void testQuicVarInt2Byte() {
        // 2-byte encoding: 64-16383
        long[] testValues = { 64, 100, 1000, 16383 };
        for (long v : testValues) {
            byte[] encoded = QuicVarInt.encode(v);
            assertEquals(2, encoded.length);
            long[] decoded = QuicVarInt.decode(encoded, 0);
            assertEquals(v, decoded[0]);
            assertEquals(2L, decoded[1]);
        }
    }

    @Test
    public void testQuicVarInt4Byte() {
        // 4-byte encoding: 16384-1073741823
        long[] testValues = { 16384, 100000, 1073741823L };
        for (long v : testValues) {
            byte[] encoded = QuicVarInt.encode(v);
            assertEquals(4, encoded.length);
            long[] decoded = QuicVarInt.decode(encoded, 0);
            assertEquals(v, decoded[0]);
            assertEquals(4L, decoded[1]);
        }
    }

    @Test
    public void testQuicVarIntEncodedLength() {
        assertEquals(1, QuicVarInt.encodedLength(0));
        assertEquals(1, QuicVarInt.encodedLength(63));
        assertEquals(2, QuicVarInt.encodedLength(64));
        assertEquals(2, QuicVarInt.encodedLength(16383));
        assertEquals(4, QuicVarInt.encodedLength(16384));
        assertEquals(4, QuicVarInt.encodedLength(1073741823L));
        assertEquals(8, QuicVarInt.encodedLength(1073741824L));
    }

    @Test
    public void testQuicVarIntEncodeTo() {
        byte[] dst = new byte[16];
        int len = QuicVarInt.encodeTo(dst, 0, 42);
        assertEquals(1, len);
        long[] decoded = QuicVarInt.decode(dst, 0);
        assertEquals(42L, decoded[0]);

        len = QuicVarInt.encodeTo(dst, 0, 500);
        assertEquals(2, len);
        decoded = QuicVarInt.decode(dst, 0);
        assertEquals(500L, decoded[0]);
    }

    // ========================= Decoder Close/Cleanup Tests =========================

    @Test
    public void testDecoderCloseCleanup() throws Throwable {
        Http3FrameDecoder decoder = new Http3FrameDecoder(true);

        // Simulate some activity
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/test");
        req.headers().add("host", "localhost");

        Http3FrameEncoder encoder = new Http3FrameEncoder(false);
        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        encIn.add(req);
        encoder.onMessage(mockContext(), encIn, encOut);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        decoder.onMessage(mockContext(), decIn, decOut);

        // Close should clean up streams
        decoder.onClose(mockContext());
    }

    // ========================= Helper Methods =========================

    private static byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays)
            totalLen += a.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertEquals("Array length mismatch", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Mismatch at index " + i, expected[i], actual[i]);
        }
    }
}
