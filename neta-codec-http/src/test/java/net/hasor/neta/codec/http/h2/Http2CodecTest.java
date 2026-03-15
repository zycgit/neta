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
    private static final String name     = "test";
    private static final int    poolSize = 8;

    // ========================= Mock & Helpers =========================

    private static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static ProtoContext mockContext() {
        java.util.Map<Class<?>, Object> contextMap = new java.util.concurrent.ConcurrentHashMap<>();
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            if ("context".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                } else if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
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

    // ========================= Inner Queue Helpers =========================

    /** Encodes HttpObject via client encoder, then decodes via server decoder. Returns decoded objects. */
    private List<HttpObject> clientToServer(HttpObject... messages) throws Throwable {
        // Encode: HttpObject → Http2Frame → ByteBuf
        Http2HttpToFrameEncoder httpToFrame = new Http2HttpToFrameEncoder(false); // client
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();

        ProtoContext encCtx = mockContext();
        httpToFrame.onInit(name, poolSize, encCtx);

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        httpToFrame.onMessage(encCtx, encIn, encodeBridge);
        frameEncoder.onMessage(encCtx, encodeBridge, encOut);

        // Decode: ByteBuf → Http2Frame → HttpObject
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true); // server
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();

        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToHttp.onInit(name, poolSize, decCtx);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        frameDecoder.onMessage(decCtx, decIn, decodeBridge);
        frameToHttp.onMessage(decCtx, decodeBridge, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Encodes HttpObject via server encoder, then decodes via client decoder. Returns decoded objects. */
    private List<HttpObject> serverToClient(HttpObject... messages) throws Throwable {
        // Encode: HttpObject → Http2Frame → ByteBuf
        Http2HttpToFrameEncoder httpToFrame = new Http2HttpToFrameEncoder(true); // server
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();

        ProtoContext encCtx = mockContext();
        httpToFrame.onInit(name, poolSize, encCtx);

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        httpToFrame.onMessage(encCtx, encIn, encodeBridge);
        frameEncoder.onMessage(encCtx, encodeBridge, encOut);

        // Decode: ByteBuf → Http2Frame → HttpObject
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(false); // client
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(false);
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();

        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToHttp.onInit(name, poolSize, decCtx);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        frameDecoder.onMessage(decCtx, decIn, decodeBridge);
        frameToHttp.onMessage(decCtx, decodeBridge, decOut);

        List<HttpObject> result = new ArrayList<>();
        while (decOut.size() > 0)
            result.add(decOut.poll());
        return result;
    }

    /** Encodes HttpObject into frames, then decodes only to the intermediate Http2Message layer. */
    private List<Http2Message> clientToServerMessages(HttpObject... messages) throws Throwable {
        Http2HttpToMessageEncoder httpToMessage = new Http2HttpToMessageEncoder(false);
        Http2MessageToFrameEncoder messageToFrame = new Http2MessageToFrameEncoder(false);
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2MessageBridgeQueue messageBridge = new Http2MessageBridgeQueue();
        Http2FrameBridgeQueue frameBridge = new Http2FrameBridgeQueue();

        ProtoContext encCtx = mockContext();
        httpToMessage.onInit(name, poolSize, encCtx);
        messageToFrame.onInit(name, poolSize, encCtx);

        SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            encIn.add(msg);
        httpToMessage.onMessage(encCtx, encIn, messageBridge);
        messageToFrame.onMessage(encCtx, messageBridge, frameBridge);
        frameEncoder.onMessage(encCtx, frameBridge, encOut);

        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToMessageDecoder frameToMessage = new Http2FrameToMessageDecoder(true);
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();

        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToMessage.onInit(name, poolSize, decCtx);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        Http2MessageBridgeQueue decOut = new Http2MessageBridgeQueue();
        while (encOut.size() > 0)
            decIn.add(encOut.poll());
        frameDecoder.onMessage(decCtx, decIn, decodeBridge);
        frameToMessage.onMessage(decCtx, decodeBridge, decOut);

        List<Http2Message> result = new ArrayList<>();
        while (decOut.hasMore())
            result.add(decOut.takeMessage());
        return result;
    }

    // ========================= Round-Trip Helpers =========================

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

    @Test
    public void testRoundTripGetRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/index.html");
        req.addHeader("host", "www.example.com");
        req.addHeader("accept", "text/html");

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
        req.addHeader("host", "api.example.com");
        req.addHeader("content-type", "application/json");

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
    public void testRoundTripViaHttp2MessageLayer() throws Throwable {
        String body = "hello-h2-message";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/msg", bodyBuf);
        req.addHeader("host", "example.com");
        req.addHeader("content-type", "text/plain");

        List<Http2Message> decoded = clientToServerMessages(req);
        Http2HeadersMessage headers = null;
        Http2DataMessage data = null;
        for (Http2Message message : decoded) {
            if (message instanceof Http2HeadersMessage && headers == null) {
                headers = (Http2HeadersMessage) message;
            } else if (message instanceof Http2DataMessage && data == null) {
                data = (Http2DataMessage) message;
            }
        }

        assertNotNull(headers);
        assertNotNull(data);
        assertEquals("POST", headers.headers().getString(":method"));
        assertEquals("/msg", headers.headers().getString(":path"));
        assertFalse(headers.endStream());

        assertTrue(data.endStream());
        String text = data.content().readString(data.content().readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, text);
    }

    @Test
    public void testMessageToHttpDecoder() throws Throwable {
        ProtoContext ctx = mockContext();
        Http2FrameToMessageDecoder frameToMessage = new Http2FrameToMessageDecoder(true);
        Http2MessageToHttpDecoder messageToHttp = new Http2MessageToHttpDecoder();
        frameToMessage.onInit(name, poolSize, ctx);
        messageToHttp.onInit(name, poolSize, ctx);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader(":method", "GET");
        headers.addHeader(":path", "/decode");
        headers.addHeader(":authority", "example.com");
        Http2HeadersMessage headersMessage = new Http2HeadersMessage(3, headers, false);

        ByteBuf dataBuf = ByteBufAllocator.DEFAULT.buffer(4);
        dataBuf.writeString("done", StandardCharsets.US_ASCII);
        dataBuf.markWriter();
        Http2DataMessage dataMessage = new Http2DataMessage(3, dataBuf, true);

        SimpleProtoRcvQueue<Http2Message> in = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> out = new SimpleProtoSndQueue<>();
        in.add(headersMessage);
        in.add(dataMessage);
        messageToHttp.onMessage(ctx, in, out);

        HttpRequest request = (HttpRequest) out.poll();
        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/decode", request.uri());

        LastHttpHeaders httpHeaders = (LastHttpHeaders) out.poll();
        assertEquals("example.com", httpHeaders.getString(HttpHeaderNames.HOST));

        LastHttpContent last = (LastHttpContent) out.poll();
        String text = last.content().readString(last.content().readableBytes(), StandardCharsets.US_ASCII);
        assertEquals("done", text);
    }

    // ========================= Round-Trip Request Tests =========================

    @Test
    public void testRoundTripResponse() throws Throwable {
        String body = "Hello, HTTP/2!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, bodyBuf);
        resp.addHeader("content-type", "text/plain");

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
        req.addHeader("host", "localhost");
        List<HttpObject> serverSide = clientToServer(req);
        assertTrue(serverSide.size() >= 1);

        // Server → Client
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        List<HttpObject> clientSide = serverToClient(resp);
        assertTrue(clientSide.size() >= 1);
    }

    @Test
    public void testHeadRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.HEAD, "/status");
        req.addHeader("host", "check.example.com");

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
        req.addHeader("host", "api.example.com");
        req.addHeader("content-type", "text/plain");

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

    // ========================= HTTP Method Tests =========================

    @Test
    public void testDeleteRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.DELETE, "/resource/42");
        req.addHeader("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.DELETE, received.method());
        assertEquals("/resource/42", received.uri());
    }

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

    // ========================= Response Status Tests =========================

    @Test
    public void testMultipleHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/multi");
        req.addHeader("host", "example.com");
        req.addHeader("accept", "text/html");
        req.addHeader("accept-language", "en-US");
        req.addHeader("cache-control", "no-cache");
        req.addHeader("user-agent", "Neta/1.0");
        req.addHeader("x-custom-header", "custom-value");

        List<Http2Message> decoded = clientToServerMessages(req);
        Http2HeadersMessage headers = null;
        for (Http2Message message : decoded) {
            if (message instanceof Http2HeadersMessage) {
                headers = (Http2HeadersMessage) message;
                break;
            }
        }
        assertNotNull(headers);
        assertEquals("text/html", headers.headers().getString("accept"));
        assertEquals("en-US", headers.headers().getString("accept-language"));
        assertEquals("no-cache", headers.headers().getString("cache-control"));
    }

    @Test
    public void testManyHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/many-headers");
        req.addHeader("host", "localhost");
        for (int i = 0; i < 50; i++) {
            req.addHeader("x-header-" + i, "value-" + i);
        }

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
    }

    // ========================= Header Tests =========================

    @Test
    public void testGetRequestNoBody() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/");
        req.addHeader("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.GET, received.method());
        assertEquals("/", received.uri());
    }

    @Test
    public void testEmptyBody() throws Throwable {
        ByteBuf emptyBuf = ByteBufAllocator.DEFAULT.buffer(0);
        emptyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/empty", emptyBuf);
        req.addHeader("host", "localhost");

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
        req.addHeader("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri.toString(), received.uri());
    }

    // ========================= Boundary Tests =========================

    @Test
    public void testUriWithSpecialCharacters() throws Throwable {
        String uri = "/path/to/resource?q=hello%20world&lang=en&special=%E4%B8%AD%E6%96%87";
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, uri);
        req.addHeader("host", "localhost");

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
        req.addHeader("host", "localhost");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() >= 1);
    }

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
        req.addHeader("host", "localhost");

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
        Http2HttpToFrameEncoder httpToFrame = new Http2HttpToFrameEncoder(false); // client
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true); // server
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);

        ProtoContext encCtx = mockContext();
        httpToFrame.onInit(name, poolSize, encCtx);
        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToHttp.onInit(name, poolSize, decCtx);

        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();
        for (int i = 0; i < 20; i++) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/page/" + i);
            req.addHeader("host", "localhost");
            encIn.add(req);
            httpToFrame.onMessage(encCtx, encIn, encodeBridge);
            frameEncoder.onMessage(encCtx, encodeBridge, encOut);
            while (encOut.size() > 0)
                allEncOut.list.add(encOut.poll());
        }

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        Http2FrameBridgeQueue decodeBridge = new Http2FrameBridgeQueue();
        while (allEncOut.size() > 0)
            decIn.add(allEncOut.poll());
        frameDecoder.onMessage(decCtx, decIn, decodeBridge);
        frameToHttp.onMessage(decCtx, decodeBridge, decOut);

        int requestCount = 0;
        while (decOut.size() > 0) {
            if (decOut.poll() instanceof HttpRequest)
                requestCount++;
        }
        assertTrue("Expected 20 requests, got " + requestCount, requestCount >= 20);
    }

    // ========================= Stress Tests =========================

    @Test
    public void testHpackEncodeDecodeRoundTrip() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        DefaultHttpHeaders original = new DefaultHttpHeaders();
        original.addHeader(":method", "GET");
        original.addHeader(":path", "/");
        original.addHeader(":scheme", "https");
        original.addHeader(":authority", "example.com");
        original.addHeader("accept", "text/html");
        original.addHeader("user-agent", "test");

        byte[] encoded = encoder.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("GET", decoded.getString(":method"));
        assertEquals("/", decoded.getString(":path"));
        assertEquals("https", decoded.getString(":scheme"));
        assertEquals("example.com", decoded.getString(":authority"));
        assertEquals("text/html", decoded.getString("accept"));
        assertEquals("test", decoded.getString("user-agent"));
    }

    @Test
    public void testHpackStaticTableIndexing() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 8192);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader(":method", "GET");
        headers.addHeader(":path", "/");
        headers.addHeader(":scheme", "https");

        byte[] encoded = encoder.encode(headers);
        DefaultHttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);

        assertEquals("GET", decoded.getString(":method"));
        assertEquals("/", decoded.getString(":path"));
        assertEquals("https", decoded.getString(":scheme"));
    }

    // ========================= HPACK Encoder/Decoder Unit Tests =========================

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
    public void testRawSettingsFrame() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] data = concat(CLIENT_PREFACE, frameHeader(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0));

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));
        frameDecoder.onMessage(ctx, decIn, bridge);
        frameToHttp.onMessage(ctx, bridge, decOut);
        assertEquals(0, decOut.size());
    }

    @Test
    public void testRawWindowUpdateFrame() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] payload = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.WINDOW_UPDATE, Http2Flags.NONE, 0), payload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));
        frameDecoder.onMessage(ctx, decIn, bridge);
        frameToHttp.onMessage(ctx, bridge, decOut);
        assertEquals(0, decOut.size());
    }

    // ========================= Raw Frame Tests (Direct Decoder) =========================

    @Test
    public void testInvalidConnectionPreface() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();
        decIn.add(toByteBuf("NOT A VALID HTTP/2 PREFACE!!".getBytes(StandardCharsets.US_ASCII)));

        try {
            frameDecoder.onMessage(ctx, decIn, bridge);
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

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] badPayload = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(5, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(ctx, decIn, bridge);
            frameToHttp.onMessage(ctx, bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("multiple of 6"));
        }
    }

    // ========================= Bad Packet Tests =========================

    @Test
    public void testRstStreamWrongPayloadSize() throws Throwable {
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true);
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);
        Http2FrameBridgeQueue bridge = new Http2FrameBridgeQueue();

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] badPayload = new byte[] { 0x01, 0x02 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(2, Http2FrameType.RST_STREAM, Http2Flags.NONE, 1), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(ctx, decIn, bridge);
            frameToHttp.onMessage(ctx, bridge, decOut);
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

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.WINDOW_UPDATE, Http2Flags.NONE, 0), payload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(ctx, decIn, bridge);
            frameToHttp.onMessage(ctx, bridge, decOut);
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

        ProtoContext ctx = mockContext();
        frameDecoder.onInit(name, poolSize, ctx);
        frameToHttp.onInit(name, poolSize, ctx);

        byte[] badPayload = new byte[] { 0x00, 0x00, 0x00, 0x01 };
        byte[] data = concat(CLIENT_PREFACE, frameHeader(4, Http2FrameType.GOAWAY, Http2Flags.NONE, 0), badPayload);

        SimpleProtoRcvQueue<ByteBuf> decIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> decOut = new SimpleProtoSndQueue<>();
        decIn.add(toByteBuf(data));

        try {
            frameDecoder.onMessage(ctx, decIn, bridge);
            frameToHttp.onMessage(ctx, bridge, decOut);
            fail("Expected HttpProtocolException");
        } catch (HttpProtocolException e) {
            assertTrue(e.getMessage().contains("GOAWAY"));
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

    // ========================= Constants and Enums Tests =========================

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
}
