package net.hasor.neta.codec.http.h2;

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
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive tests for HTTP/2 codec (encoder + decoder) implementation.
 * Uses direct handler invocation instead of VrtChannel for round-trip testing.
 */
public class Http2CodecTest {

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

    /** Builds an HTTP/2 frame header. */
    private static byte[] frameHeader(int length, int type, int flags, int streamId) {
        byte[] header = new byte[9];
        header[0] = (byte) ((length >>> 16) & 0xFF);
        header[1] = (byte) ((length >>> 8) & 0xFF);
        header[2] = (byte) (length & 0xFF);
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) ((streamId >>> 24) & 0x7F);
        header[6] = (byte) ((streamId >>> 16) & 0xFF);
        header[7] = (byte) ((streamId >>> 8) & 0xFF);
        header[8] = (byte) (streamId & 0xFF);
        return header;
    }

    /** Concatenates byte arrays. */
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

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

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
        // Encode: HttpObject → Http2Frame → ByteBuf
        HttpObjectToHttp2FrameEncoder httpToFrame = new HttpObjectToHttp2FrameEncoder(false); // client
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        httpToFrame.onMessage(mockContext(), encIn, encodeBridge);
        frameEncoder.onMessage(mockContext(), encodeBridge, encOut);

        // Decode: ByteBuf → Http2Frame → HttpObject
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true); // server
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        frameDecoder.onMessage(mockContext(), decIn, decodeBridge);
        frameToHttp.onMessage(mockContext(), decodeBridge, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Encodes HttpObject via server encoder, then decodes via client decoder. Returns decoded objects. */
    private List<HttpObject> serverToClient(HttpObject... messages) throws Throwable {
        // Encode: HttpObject → Http2Frame → ByteBuf
        HttpObjectToHttp2FrameEncoder httpToFrame = new HttpObjectToHttp2FrameEncoder(true); // server
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        httpToFrame.onMessage(mockContext(), encIn, encodeBridge);
        frameEncoder.onMessage(mockContext(), encodeBridge, encOut);

        // Decode: ByteBuf → Http2Frame → HttpObject
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(false); // client
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(false);
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        frameDecoder.onMessage(mockContext(), decIn, decodeBridge);
        frameToHttp.onMessage(mockContext(), decodeBridge, decOut);

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
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/index.html");
        req.headers().add("host", "www.example.com");
        req.headers().add("accept", "text/html");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals("/index.html", received.uri());
        assertEquals(HttpMethod.GET, received.method());
        assertEquals(HttpVersion.HTTP_2_0, received.protocolVersion());
    }

    @Test
    public void testRoundTripPostRequestWithBody() throws Throwable {
        String body = "{\"key\":\"value\",\"number\":42}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/data", bodyBuf);
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
        String body = "Hello, HTTP/2!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, bodyBuf);
        resp.headers().add("content-type", "text/plain");

        List<HttpObject> decoded = serverToClient(resp);
        assertTrue(decoded.size() >= 1);

        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.OK, received.status());
    }

    @Test
    public void testRoundTripBidirectional() throws Throwable {
        // Client → Server
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/hello");
        req.headers().add("host", "localhost");
        List<HttpObject> serverSide = clientToServer(req);
        assertTrue(serverSide.size() >= 1);

        // Server → Client
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        List<HttpObject> clientSide = serverToClient(resp);
        assertTrue(clientSide.size() >= 1);
    }

    // ========================= HTTP Method Tests =========================

    @Test
    public void testHeadRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.HEAD, "/status");
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

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.PUT, "/resource/1", bodyBuf);
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
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.DELETE, "/resource/42");
        req.headers().add("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.DELETE, received.method());
        assertEquals("/resource/42", received.uri());
    }

    // ========================= Response Status Tests =========================

    @Test
    public void test404Response() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_FOUND);
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

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.INTERNAL_SERVER_ERROR, bodyBuf);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, received.status());
    }

    // ========================= Header Tests =========================

    @Test
    public void testMultipleHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/multi");
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
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/many-headers");
        req.headers().add("host", "localhost");
        for (int i = 0; i < 50; i++) {
            req.headers().add("x-header-" + i, "value-" + i);
        }

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
    }

    @Test
    public void testGetRequestNoBody() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/");
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

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/empty", emptyBuf);
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

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, uri.toString());
        req.headers().add("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri.toString(), received.uri());
    }

    @Test
    public void testUriWithSpecialCharacters() throws Throwable {
        String uri = "/path/to/resource?q=hello%20world&lang=en&special=%E4%B8%AD%E6%96%87";
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, uri);
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

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/byte", bodyBuf);
        req.headers().add("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);
    }

    // ========================= Stress Tests =========================

    @Test
    public void testLargeBody64KB() throws Throwable {
        // HTTP/2 default SETTINGS_MAX_FRAME_SIZE is 16384, so use body within that limit
        byte[] bodyBytes = new byte[8192];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) (i % 256);
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/large", bodyBuf);
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
    public void testMultipleSequentialRequests() throws Throwable {
        HttpObjectToHttp2FrameEncoder httpToFrame = new HttpObjectToHttp2FrameEncoder(false); // client
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true); // server
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);

        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();
        for (int i = 0; i < 20; i++) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/page/" + i);
            req.headers().add("host", "localhost");
            encIn.add(req);
            httpToFrame.onMessage(mockContext(), encIn, encodeBridge);
            frameEncoder.onMessage(mockContext(), encodeBridge, encOut);
            while (encOut.size() > 0)
                allEncOut.list.add(encOut.poll());
        }

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();
        while (allEncOut.size() > 0)
            decIn.add(allEncOut.poll());
        frameDecoder.onMessage(mockContext(), decIn, decodeBridge);
        frameToHttp.onMessage(mockContext(), decodeBridge, decOut);

        int requestCount = 0;
        while (decOut.size() > 0) {
            if (decOut.poll() instanceof HttpRequest)
                requestCount++;
        }
        assertTrue("Expected 20 requests, got " + requestCount, requestCount >= 20);
    }

    // ========================= HPACK Encoder/Decoder Unit Tests =========================

    @Test
    public void testHpackEncodeDecodeRoundTrip() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

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
    public void testHpackStaticTableIndexing() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

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
    public void testHpackLargeHeaderValue() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        StringBuilder largeValue = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeValue.append("abcdefghij");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-large-header", largeValue.toString());

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals(largeValue.toString(), decoded.get("x-large-header"));
    }

    @Test
    public void testHpackEmptyHeaders() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        HttpHeaders headers = new HttpHeaders();
        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testHpackDynamicTableEviction() {
        HpackEncoder encoder = new HpackEncoder(64);
        HpackDecoder decoder = new HpackDecoder(64, 8192);

        HttpHeaders h1 = new HttpHeaders();
        h1.add("x-key-1", "value-one-that-is-long");
        byte[] e1 = encoder.encode(h1);
        decoder.decode(e1, 0, e1.length);

        HttpHeaders h2 = new HttpHeaders();
        h2.add("x-key-2", "value-two-that-is-long");
        byte[] e2 = encoder.encode(h2);
        HttpHeaders d2 = decoder.decode(e2, 0, e2.length);
        assertEquals("value-two-that-is-long", d2.get("x-key-2"));
    }

    // ========================= Raw Frame Tests (Direct Decoder) =========================

    @Test
    public void testRawSettingsFrame() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] data = concat(CLIENT_PREFACE, frameHeader(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0));

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));
        frameDecoder.onMessage(mockContext(), decIn, bridge);
        frameToHttp.onMessage(mockContext(), bridge, decOut);
        assertEquals(0, decOut.size());
    }

    @Test
    public void testRawWindowUpdateFrame() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] payload = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.WINDOW_UPDATE, Http2Flags.NONE, 0), payload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));
        frameDecoder.onMessage(mockContext(), decIn, bridge);
        frameToHttp.onMessage(mockContext(), bridge, decOut);
        assertEquals(0, decOut.size());
    }

    // ========================= Bad Packet Tests =========================

    @Test
    public void testInvalidConnectionPreface() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();
        decIn.add(toByteBuf("NOT A VALID HTTP/2 PREFACE!!".getBytes(StandardCharsets.US_ASCII)));

        try {
            frameDecoder.onMessage(mockContext(), decIn, bridge);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("invalid connection preface"));
        }
    }

    @Test
    public void testSettingsNotMultipleOf6() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] badPayload = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(5, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(mockContext(), decIn, bridge);
            frameToHttp.onMessage(mockContext(), bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("multiple of 6"));
        }
    }

    @Test
    public void testRstStreamWrongPayloadSize() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] badPayload = new byte[] { 0x01, 0x02 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(2, Http2FrameType.RST_STREAM, Http2Flags.NONE, 1), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(mockContext(), decIn, bridge);
            frameToHttp.onMessage(mockContext(), bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("4 bytes"));
        }
    }

    @Test
    public void testWindowUpdateZeroIncrement() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.WINDOW_UPDATE, Http2Flags.NONE, 0), payload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(mockContext(), decIn, bridge);
            frameToHttp.onMessage(mockContext(), bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("non-zero"));
        }
    }

    @Test
    public void testGoawayTooShort() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        byte[] badPayload = new byte[] { 0x00, 0x00, 0x00, 0x01 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.GOAWAY, Http2Flags.NONE, 0), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(mockContext(), decIn, bridge);
            frameToHttp.onMessage(mockContext(), bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("GOAWAY"));
        }
    }

    // ========================= Constants and Enums Tests =========================

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
}
