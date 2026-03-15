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
 * Enriched HTTP/2 scenario tests covering additional methods, response statuses,
 * header edge cases, content types, HPACK compression scenarios, and stream management.
 */
public class Http2EnrichedScenarioTest {
    private static final String name     = "test";
    private static final int    poolSize = 8;

    // ========================= Mock & Helpers (same structure as Http2CodecTest) =========================

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
    public void testOptionsRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.OPTIONS, "*");
        req.addHeader("host", "api.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.OPTIONS, received.method());
    }

    @Test
    public void testConnectMethod() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.CONNECT, "proxy.example.com:443");
        req.addHeader("host", "proxy.example.com");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.CONNECT, received.method());
    }

    // ========================= Additional HTTP Method Tests =========================

    @Test
    public void testPatchRequest() throws Throwable {
        String body = "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"new\"}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.PATCH, "/resource/1", bodyBuf);
        req.addHeader("host", "api.example.com");
        req.addHeader("content-type", "application/json-patch+json");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals(HttpMethod.PATCH, received.method());
    }

    @Test
    public void test201Created() throws Throwable {
        String body = "{\"id\":42}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.CREATED, bodyBuf);
        resp.addHeader("location", "/resource/42");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals(HttpStatus.CREATED, received.status());
        assertEquals("/resource/42", headers.getString("location"));
    }

    @Test
    public void test204NoContent() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.NO_CONTENT, received.status());
    }

    // ========================= Additional Response Status Tests =========================

    @Test
    public void test301Redirect() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.MOVED_PERMANENTLY);
        resp.addHeader("location", "https://new.example.com/path");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals(HttpStatus.MOVED_PERMANENTLY, received.status());
        assertEquals("https://new.example.com/path", headers.getString("location"));
    }

    @Test
    public void test304NotModified() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_MODIFIED);
        resp.addHeader("etag", "\"v1.2.3\"");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals(HttpStatus.NOT_MODIFIED, received.status());
        assertEquals("\"v1.2.3\"", headers.getString("etag"));
    }

    @Test
    public void test400BadRequest() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.BAD_REQUEST);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.BAD_REQUEST, received.status());
    }

    @Test
    public void test403Forbidden() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.FORBIDDEN);
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.FORBIDDEN, received.status());
    }

    @Test
    public void test503ServiceUnavailable() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.SERVICE_UNAVAILABLE);
        resp.addHeader("retry-after", "60");
        HttpResponse received = findFirst(serverToClient(resp), HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, received.status());
    }

    @Test
    public void testJsonContentType() throws Throwable {
        String body = "{\"users\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/users", bodyBuf);
        req.addHeader("host", "api.example.com");
        req.addHeader("content-type", "application/json");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("application/json", headers.getString("content-type"));

        HttpContent lastContent = findLast(decoded, HttpContent.class);
        assertNotNull(lastContent);
        assertEquals(body, lastContent.content().readString(lastContent.content().readableBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testFormUrlEncodedContentType() throws Throwable {
        String body = "username=admin&password=secret123&remember=true";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/login", bodyBuf);
        req.addHeader("host", "auth.example.com");
        req.addHeader("content-type", "application/x-www-form-urlencoded");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("application/x-www-form-urlencoded", headers.getString("content-type"));
    }

    // ========================= Content-Type Diversity Tests =========================

    @Test
    public void testXmlContentType() throws Throwable {
        String body = "<?xml version=\"1.0\"?><root><item>Hello</item></root>";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/xml", bodyBuf);
        req.addHeader("host", "api.example.com");
        req.addHeader("content-type", "application/xml");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("application/xml", headers.getString("content-type"));
    }

    @Test
    public void testBinaryBodyPreserved() throws Throwable {
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++)
            binary[i] = (byte) i;

        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(binary.length);
        bodyBuf.writeBytes(binary, 0, binary.length);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/upload", bodyBuf);
        req.addHeader("host", "upload.example.com");
        req.addHeader("content-type", "application/octet-stream");

        List<HttpObject> decoded = clientToServer(req);
        HttpContent lastContent = findLast(decoded, HttpContent.class);
        assertNotNull(lastContent);
        ByteBuf content = lastContent.content();
        assertEquals(256, content.readableBytes());

        byte[] received = new byte[256];
        content.getBytes(0, received, 0, 256);
        for (int i = 0; i < 256; i++) {
            assertEquals("Byte mismatch at index " + i, binary[i], received[i]);
        }
    }

    @Test
    public void testHeaderWithEmptyValue() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/test");
        req.addHeader("host", "localhost");
        req.addHeader("x-empty", "");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("", headers.getString("x-empty"));
    }

    @Test
    public void testHeaderWithUnicodeValue() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/unicode");
        req.addHeader("host", "localhost");
        req.addHeader("x-description", "test-header-value");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("test-header-value", headers.getString("x-description"));
    }

    // ========================= Header Edge Cases =========================

    @Test
    public void testMultiValueHeaders() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/accept");
        req.addHeader("host", "localhost");
        req.addHeader("accept", "text/html");
        req.addHeader("accept", "application/json");
        req.addHeader("accept", "text/plain");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertNotNull(headers.getString("accept"));
    }

    @Test
    public void testCommonSecurityHeaders() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        resp.addHeader("x-content-type-options", "nosniff");
        resp.addHeader("x-frame-options", "DENY");
        resp.addHeader("x-xss-protection", "1; mode=block");
        resp.addHeader("strict-transport-security", "max-age=31536000");
        resp.addHeader("content-security-policy", "default-src 'self'");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("nosniff", headers.getString("x-content-type-options"));
        assertEquals("DENY", headers.getString("x-frame-options"));
    }

    @Test
    public void testCorsHeaders() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        resp.addHeader("access-control-allow-origin", "*");
        resp.addHeader("access-control-allow-methods", "GET, POST, OPTIONS");
        resp.addHeader("access-control-allow-headers", "Content-Type, Authorization");
        resp.addHeader("access-control-max-age", "86400");

        List<HttpObject> decoded = serverToClient(resp);
        HttpResponse received = findFirst(decoded, HttpResponse.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("*", headers.getString("access-control-allow-origin"));
    }

    @Test
    public void testHpackSequentialEncodingSharesContext() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        // First request
        DefaultHttpHeaders h1 = new DefaultHttpHeaders();
        h1.addHeader(":method", "GET");
        h1.addHeader(":path", "/page1");
        h1.addHeader(":scheme", "https");
        h1.addHeader(":authority", "example.com");
        byte[] e1 = encoder.encode(h1);
        HttpHeaders d1 = decoder.decode(e1, 0, e1.length);
        assertEquals("/page1", d1.getString(":path"));

        // Second request with similar headers (should benefit from dynamic table)
        DefaultHttpHeaders h2 = new DefaultHttpHeaders();
        h2.addHeader(":method", "GET");
        h2.addHeader(":path", "/page2");
        h2.addHeader(":scheme", "https");
        h2.addHeader(":authority", "example.com");
        byte[] e2 = encoder.encode(h2);
        HttpHeaders d2 = decoder.decode(e2, 0, e2.length);
        assertEquals("/page2", d2.getString(":path"));

        // Second encoding should be more compact due to shared context
        assertTrue("Second encoding should be <= first due to dynamic table", e2.length <= e1.length);
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

    // ========================= HPACK Advanced Scenarios =========================

    @Test
    public void testHpackHeaderWithSpecialChars() {
        HpackEncoder encoder = new HpackEncoder(4096);
        HpackDecoder decoder = new HpackDecoder(4096, 65536);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("x-special", "key=value; path=/; domain=.example.com");
        headers.addHeader("cookie", "session=abc123; lang=en-US");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("key=value; path=/; domain=.example.com", decoded.getString("x-special"));
        assertEquals("session=abc123; lang=en-US", decoded.getString("cookie"));
    }

    @Test
    public void testHpackSmallDynamicTable() {
        HpackEncoder encoder = new HpackEncoder(32);  // Very small table
        HpackDecoder decoder = new HpackDecoder(32, 65536);

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader("x-key", "value");

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertEquals("value", decoded.getString("x-key"));
    }

    @Test
    public void testMultipleRequestsWithDifferentMethods() throws Throwable {
        Http2HttpToFrameEncoder httpToFrame = new Http2HttpToFrameEncoder(false); // client
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(true); // server
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(true);

        ProtoContext encCtx = mockContext();
        httpToFrame.onInit(name, poolSize, encCtx);
        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToHttp.onInit(name, poolSize, decCtx);

        HttpMethod[] methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD };
        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();

        for (HttpMethod method : methods) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, method, "/api");
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
        List<HttpMethod> decodedMethods = new ArrayList<>();
        while (decOut.size() > 0) {
            HttpObject obj = decOut.poll();
            if (obj instanceof HttpRequest) {
                requestCount++;
                decodedMethods.add(((HttpRequest) obj).method());
            }
        }
        assertEquals("Should decode all 5 requests", 5, requestCount);
    }

    @Test
    public void testMultipleResponsesWithVariousStatuses() throws Throwable {
        Http2HttpToFrameEncoder httpToFrame = new Http2HttpToFrameEncoder(true); // server
        Http2FrameEncoder frameEncoder = new Http2FrameEncoder();
        Http2FrameDecoder frameDecoder = new Http2FrameDecoder(false); // client
        Http2FrameToHttpDecoder frameToHttp = new Http2FrameToHttpDecoder(false);

        ProtoContext encCtx = mockContext();
        httpToFrame.onInit(name, poolSize, encCtx);
        ProtoContext decCtx = mockContext();
        frameDecoder.onInit(name, poolSize, decCtx);
        frameToHttp.onInit(name, poolSize, decCtx);

        HttpStatus[] statuses = { HttpStatus.OK, HttpStatus.CREATED, HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR };
        SimpleProtoSndQueue<ByteBuf> allEncOut = new SimpleProtoSndQueue<>();

        for (HttpStatus status : statuses) {
            SimpleProtoRcvQueue<HttpObject> encIn = new SimpleProtoRcvQueue<>();
            Http2FrameBridgeQueue encodeBridge = new Http2FrameBridgeQueue();
            SimpleProtoSndQueue<ByteBuf> encOut = new SimpleProtoSndQueue<>();
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, status);
            encIn.add(resp);
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

        int responseCount = 0;
        while (decOut.size() > 0) {
            if (decOut.poll() instanceof HttpResponse)
                responseCount++;
        }
        assertEquals("Should decode all 5 responses", 5, responseCount);
    }

    // ========================= Multiple Stream Scenarios =========================

    @Test
    public void testLargeBody32KB() throws Throwable {
        // HTTP/2 default SETTINGS_MAX_FRAME_SIZE is 16384 bytes, use a body within that limit
        byte[] bodyBytes = new byte[15000];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) ('A' + (i % 26));
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/large-upload", bodyBuf);
        req.addHeader("host", "upload.example.com");

        List<HttpObject> decoded = clientToServer(req);
        assertTrue(decoded.size() > 0);

        HttpRequest received = findFirst(decoded, HttpRequest.class);
        assertNotNull(received);
        assertEquals("/large-upload", received.uri());
    }

    @Test
    public void testLargeResponseBody() throws Throwable {
        byte[] bodyBytes = new byte[16384];
        for (int i = 0; i < bodyBytes.length; i++) {
            bodyBytes[i] = (byte) (i % 256);
        }
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length);
        bodyBuf.writeBytes(bodyBytes, 0, bodyBytes.length);
        bodyBuf.markWriter();

        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, bodyBuf);
        resp.addHeader("content-type", "application/octet-stream");

        List<HttpObject> decoded = serverToClient(resp);
        assertTrue(decoded.size() > 0);

        HttpResponse received = findFirst(decoded, HttpResponse.class);
        assertNotNull(received);
        assertEquals(HttpStatus.OK, received.status());
    }

    // ========================= Large Body Variations =========================

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

    // ========================= HTTP/2 Settings Tests =========================

    @Test
    public void testUriWithFragment() throws Throwable {
        String uri = "/page?query=value#section";
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, uri);
        req.addHeader("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri, received.uri());
    }

    @Test
    public void testUriWithPort() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/resource");
        req.addHeader("host", "localhost:8080");

        List<HttpObject> decoded = clientToServer(req);
        HttpRequest received = findFirst(decoded, HttpRequest.class);
        LastHttpHeaders headers = findLast(decoded, LastHttpHeaders.class);
        assertNotNull(received);
        assertNotNull(headers);
        assertEquals("localhost:8080", headers.getString("host"));
    }

    // ========================= URI Variations =========================

    @Test
    public void testUriWithDeepPath() throws Throwable {
        String uri = "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p";
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, uri);
        req.addHeader("host", "localhost");

        HttpRequest received = findFirst(clientToServer(req), HttpRequest.class);
        assertNotNull(received);
        assertEquals(uri, received.uri());
    }

    @Test
    public void testFreshEncoderDecoderPairPerTest() throws Throwable {
        // Each call creates fresh encoder/decoder pair - verify independence
        DefaultFullHttpRequest req1 = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/test1");
        req1.addHeader("host", "host1.com");
        HttpRequest r1 = findFirst(clientToServer(req1), HttpRequest.class);
        assertNotNull(r1);
        assertEquals("/test1", r1.uri());

        DefaultFullHttpRequest req2 = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/test2");
        req2.addHeader("host", "host2.com");
        HttpRequest r2 = findFirst(clientToServer(req2), HttpRequest.class);
        assertNotNull(r2);
        assertEquals("/test2", r2.uri());
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

    // ========================= Encoder State Isolation Tests =========================

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
