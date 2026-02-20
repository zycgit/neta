package net.hasor.neta.codec.http3;

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
import net.hasor.neta.codec.quic.QuicFrameDecoder;
import net.hasor.neta.codec.quic.QuicFrameEncoder;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Comprehensive tests for HTTP/3 codec (encoder + decoder) implementation.
 * Uses a full 4-layer pipeline: Http3FrameEncoder → QuicFrameEncoder → QuicFrameDecoder → Http3FrameDecoder.
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

    // ========================= Pipeline Helpers =========================

    /**
     * Runs a 4-layer pipeline: Http3FrameEncoder → QuicFrameEncoder → QuicFrameDecoder → Http3FrameDecoder.
     * Client sends to server.
     */
    private List<HttpObject> clientToServer(HttpObject... messages) throws Throwable {
        Http3FrameEncoder h3Encoder = new Http3FrameEncoder(false);
        QuicFrameEncoder quicEncoder = new QuicFrameEncoder(false);
        QuicFrameDecoder quicDecoder = new QuicFrameDecoder(true);
        Http3FrameDecoder h3Decoder = new Http3FrameDecoder(true);
        ProtoContext ctx = mockContext();

        // Layer 1: HttpObject → ByteBuf (with 9-byte metadata)
        SimpleProtoRcvQueue<HttpObject> h3EncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> h3EncOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages) {
            h3EncIn.add(msg);
        }
        h3Encoder.onMessage(ctx, h3EncIn, h3EncOut);

        // Layer 2: ByteBuf (9-byte meta + data) → ByteBuf (QUIC STREAM frame)
        SimpleProtoRcvQueue<ByteBuf> quicEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> quicEncOut = new SimpleProtoSndQueue<>();
        while (h3EncOut.size() > 0)
            quicEncIn.add(h3EncOut.poll());
        quicEncoder.onMessage(ctx, quicEncIn, quicEncOut);

        // Layer 3: ByteBuf (QUIC frame) → ByteBuf (9-byte meta + data)
        SimpleProtoRcvQueue<ByteBuf> quicDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> quicDecOut = new SimpleProtoSndQueue<>();
        while (quicEncOut.size() > 0)
            quicDecIn.add(quicEncOut.poll());
        quicDecoder.onMessage(ctx, quicDecIn, quicDecOut);

        // Layer 4: ByteBuf (9-byte meta + data) → HttpObject
        SimpleProtoRcvQueue<ByteBuf> h3DecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> h3DecOut = new SimpleProtoSndQueue<>();
        while (quicDecOut.size() > 0)
            h3DecIn.add(quicDecOut.poll());
        h3Decoder.onMessage(ctx, h3DecIn, h3DecOut);

        return new ArrayList<>(h3DecOut.list);
    }

    /**
     * Runs a 4-layer pipeline for server → client direction.
     * Must pre-process a client request first so the encoder has a currentStreamId.
     */
    private List<HttpObject> serverToClient(HttpObject request, HttpObject... responses) throws Throwable {
        // First simulate client→server to set up stream state
        Http3FrameEncoder clientEncoder = new Http3FrameEncoder(false);
        QuicFrameEncoder clientQuicEnc = new QuicFrameEncoder(false);
        QuicFrameDecoder serverQuicDec = new QuicFrameDecoder(true);
        Http3FrameDecoder serverH3Dec = new Http3FrameDecoder(true);
        ProtoContext ctx = mockContext();

        // Client sends request through full pipeline
        SimpleProtoRcvQueue<HttpObject> cEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> cEncOut = new SimpleProtoSndQueue<>();
        cEncIn.add(request);
        clientEncoder.onMessage(ctx, cEncIn, cEncOut);

        SimpleProtoRcvQueue<ByteBuf> cqEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> cqEncOut = new SimpleProtoSndQueue<>();
        while (cEncOut.size() > 0)
            cqEncIn.add(cEncOut.poll());
        clientQuicEnc.onMessage(ctx, cqEncIn, cqEncOut);

        SimpleProtoRcvQueue<ByteBuf> sqDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> sqDecOut = new SimpleProtoSndQueue<>();
        while (cqEncOut.size() > 0)
            sqDecIn.add(cqEncOut.poll());
        serverQuicDec.onMessage(ctx, sqDecIn, sqDecOut);

        SimpleProtoRcvQueue<ByteBuf> shDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> shDecOut = new SimpleProtoSndQueue<>();
        while (sqDecOut.size() > 0)
            shDecIn.add(sqDecOut.poll());
        serverH3Dec.onMessage(ctx, shDecIn, shDecOut);

        // Now server sends response back through its own encoder pipeline
        Http3FrameEncoder serverEncoder = new Http3FrameEncoder(true);
        QuicFrameEncoder serverQuicEnc = new QuicFrameEncoder(true);
        QuicFrameDecoder clientQuicDec = new QuicFrameDecoder(false);
        Http3FrameDecoder clientH3Dec = new Http3FrameDecoder(false);

        // Layer 1: HttpObject → ByteBuf
        SimpleProtoRcvQueue<HttpObject> sEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> sEncOut = new SimpleProtoSndQueue<>();
        for (HttpObject resp : responses) {
            sEncIn.add(resp);
        }
        serverEncoder.onMessage(ctx, sEncIn, sEncOut);

        // Layer 2: ByteBuf → QUIC frame
        SimpleProtoRcvQueue<ByteBuf> sqEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> sqEncOut = new SimpleProtoSndQueue<>();
        while (sEncOut.size() > 0)
            sqEncIn.add(sEncOut.poll());
        serverQuicEnc.onMessage(ctx, sqEncIn, sqEncOut);

        // Layer 3: QUIC frame → ByteBuf
        SimpleProtoRcvQueue<ByteBuf> cqDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> cqDecOut = new SimpleProtoSndQueue<>();
        while (sqEncOut.size() > 0)
            cqDecIn.add(sqEncOut.poll());
        clientQuicDec.onMessage(ctx, cqDecIn, cqDecOut);

        // Layer 4: ByteBuf → HttpObject
        SimpleProtoRcvQueue<ByteBuf> chDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> chDecOut = new SimpleProtoSndQueue<>();
        while (cqDecOut.size() > 0)
            chDecIn.add(cqDecOut.poll());
        clientH3Dec.onMessage(ctx, chDecIn, chDecOut);

        return new ArrayList<>(chDecOut.list);
    }

    /**
     * Simplified one-way pipeline: encodes then decodes without full bidirectional flow.
     */
    private List<HttpObject> encodeDecode(boolean serverMode, HttpObject... messages) throws Throwable {
        Http3FrameEncoder h3Encoder = new Http3FrameEncoder(serverMode);
        QuicFrameEncoder quicEncoder = new QuicFrameEncoder(serverMode);
        QuicFrameDecoder quicDecoder = new QuicFrameDecoder(!serverMode);
        Http3FrameDecoder h3Decoder = new Http3FrameDecoder(!serverMode);
        ProtoContext ctx = mockContext();

        SimpleProtoRcvQueue<HttpObject> h3EncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> h3EncOut = new SimpleProtoSndQueue<>();
        for (HttpObject msg : messages)
            h3EncIn.add(msg);
        h3Encoder.onMessage(ctx, h3EncIn, h3EncOut);

        SimpleProtoRcvQueue<ByteBuf> quicEncIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> quicEncOut = new SimpleProtoSndQueue<>();
        while (h3EncOut.size() > 0)
            quicEncIn.add(h3EncOut.poll());
        quicEncoder.onMessage(ctx, quicEncIn, quicEncOut);

        SimpleProtoRcvQueue<ByteBuf> quicDecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<ByteBuf> quicDecOut = new SimpleProtoSndQueue<>();
        while (quicEncOut.size() > 0)
            quicDecIn.add(quicEncOut.poll());
        quicDecoder.onMessage(ctx, quicDecIn, quicDecOut);

        SimpleProtoRcvQueue<ByteBuf> h3DecIn = new SimpleProtoRcvQueue<>();
        SimpleProtoSndQueue<HttpObject> h3DecOut = new SimpleProtoSndQueue<>();
        while (quicDecOut.size() > 0)
            h3DecIn.add(quicDecOut.poll());
        h3Decoder.onMessage(ctx, h3DecIn, h3DecOut);

        return new ArrayList<>(h3DecOut.list);
    }

    private <T> T findFirst(List<HttpObject> objects, Class<T> type) {
        for (HttpObject obj : objects) {
            if (type.isInstance(obj))
                return type.cast(obj);
        }
        return null;
    }

    private <T> T findLast(List<HttpObject> objects, Class<T> type) {
        T last = null;
        for (HttpObject obj : objects) {
            if (type.isInstance(obj))
                last = type.cast(obj);
        }
        return last;
    }

    // ========================= Round-Trip Tests (Client → Server) =========================

    @Test
    public void testClientToServerGetRequest() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/index.html");
        req.headers().add("Host", "example.com");

        List<HttpObject> results = clientToServer(req);
        assertFalse("Expected at least one decoded object", results.isEmpty());

        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull("Expected HttpRequest in output", decoded);
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/index.html", decoded.uri());
    }

    @Test
    public void testClientToServerPostRequest() throws Throwable {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(64);
        body.writeString("Hello HTTP/3", StandardCharsets.UTF_8);
        body.markWriter();
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/api/data", body);
        req.headers().add("Host", "example.com");
        req.headers().add("Content-Type", "text/plain");

        List<HttpObject> results = clientToServer(req);
        assertFalse("Expected decoded output", results.isEmpty());

        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/api/data", decoded.uri());
    }

    @Test
    public void testClientToServerWithEmptyBody() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.DELETE, "/resource/42");
        req.headers().add("Host", "example.com");

        List<HttpObject> results = clientToServer(req);
        assertFalse(results.isEmpty());

        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        assertEquals(HttpMethod.DELETE, decoded.method());
        assertEquals("/resource/42", decoded.uri());
    }

    @Test
    public void testClientToServerMultipleMethods() throws Throwable {
        HttpMethod[] methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PATCH };
        for (HttpMethod method : methods) {
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, method, "/test");
            req.headers().add("Host", "example.com");

            List<HttpObject> results = clientToServer(req);
            assertFalse("Expected output for method " + method, results.isEmpty());

            HttpRequest decoded = findFirst(results, HttpRequest.class);
            assertNotNull("Expected request for method " + method, decoded);
            assertEquals(method, decoded.method());
        }
    }

    // ========================= Round-Trip Tests (Server → Client) =========================

    @Test
    public void testServerToClientResponse() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
        resp.headers().add("Content-Type", "text/html");

        List<HttpObject> results = encodeDecode(true, resp);
        assertFalse(results.isEmpty());

        HttpResponse decoded = findFirst(results, HttpResponse.class);
        assertNotNull("Expected HttpResponse in output", decoded);
        assertEquals(200, decoded.status().code());
    }

    @Test
    public void testServerToClient404Response() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NOT_FOUND);

        List<HttpObject> results = encodeDecode(true, resp);
        assertFalse(results.isEmpty());

        HttpResponse decoded = findFirst(results, HttpResponse.class);
        assertNotNull(decoded);
        assertEquals(404, decoded.status().code());
    }

    @Test
    public void testServerToClient500Response() throws Throwable {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.INTERNAL_SERVER_ERROR);

        List<HttpObject> results = encodeDecode(true, resp);
        assertFalse(results.isEmpty());

        HttpResponse decoded = findFirst(results, HttpResponse.class);
        assertNotNull(decoded);
        assertEquals(500, decoded.status().code());
    }

    @Test
    public void testServerToClientResponseWithBody() throws Throwable {
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(128);
        body.writeString("{\"status\":\"ok\"}", StandardCharsets.UTF_8);
        body.markWriter();
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, body);
        resp.headers().add("Content-Type", "application/json");

        List<HttpObject> results = encodeDecode(true, resp);
        assertFalse(results.isEmpty());

        HttpResponse decoded = findFirst(results, HttpResponse.class);
        assertNotNull(decoded);
        assertEquals(200, decoded.status().code());
    }

    // ========================= Header Tests =========================

    @Test
    public void testCustomHeadersPreservation() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/test");
        req.headers().add("Host", "example.com");
        req.headers().add("X-Custom-Header", "custom-value");
        req.headers().add("Accept", "application/json");

        List<HttpObject> results = clientToServer(req);
        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        assertEquals("custom-value", decoded.headers().get("x-custom-header"));
        assertEquals("application/json", decoded.headers().get("accept"));
    }

    @Test
    public void testHostToAuthorityMapping() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/test");
        req.headers().add("Host", "www.example.org:8443");

        List<HttpObject> results = clientToServer(req);
        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        // Http3FrameEncoder maps Host → :authority, and decoder maps :authority → host
        assertEquals("www.example.org:8443", decoded.headers().get("host"));
    }

    // ========================= Boundary & Stress Tests =========================

    @Test
    public void testLargeBodyRoundTrip() throws Throwable {
        byte[] bigBody = new byte[65536];
        for (int i = 0; i < bigBody.length; i++) {
            bigBody[i] = (byte) ('A' + (i % 26));
        }
        ByteBuf body = ByteBufAllocator.DEFAULT.buffer(bigBody.length);
        body.writeBytes(bigBody, 0, bigBody.length);
        body.markWriter();
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/upload", body);
        req.headers().add("Host", "example.com");

        List<HttpObject> results = clientToServer(req);
        assertFalse(results.isEmpty());

        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        assertEquals(HttpMethod.POST, decoded.method());
    }

    @Test
    public void testSpecialCharactersInUri() throws Throwable {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/path?key=%E4%B8%AD%E6%96%87&foo=bar");
        req.headers().add("Host", "example.com");

        List<HttpObject> results = clientToServer(req);
        HttpRequest decoded = findFirst(results, HttpRequest.class);
        assertNotNull(decoded);
        assertEquals("/path?key=%E4%B8%AD%E6%96%87&foo=bar", decoded.uri());
    }

    @Test
    public void testMultipleSequentialRequests() throws Throwable {
        for (int i = 0; i < 20; i++) {
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/page/" + i);
            req.headers().add("Host", "example.com");
            req.headers().add("X-Request-Id", String.valueOf(i));

            List<HttpObject> results = clientToServer(req);
            assertFalse("Request " + i + " should produce output", results.isEmpty());

            HttpRequest decoded = findFirst(results, HttpRequest.class);
            assertNotNull(decoded);
            assertEquals("/page/" + i, decoded.uri());
        }
    }

    // ========================= QPACK Unit Tests =========================

    @Test
    public void testQpackRoundTrip() {
        QpackEncoder encoder = new QpackEncoder();
        QpackDecoder decoder = new QpackDecoder();

        HttpHeaders headers = new HttpHeaders();
        headers.add(":method", "GET");
        headers.add(":path", "/index.html");
        headers.add(":scheme", "https");
        headers.add(":authority", "example.com");
        headers.add("accept", "text/html");

        byte[] encoded = encoder.encode(headers);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertEquals("GET", decoded.get(":method"));
        assertEquals("/index.html", decoded.get(":path"));
        assertEquals("https", decoded.get(":scheme"));
        assertEquals("example.com", decoded.get(":authority"));
        assertEquals("text/html", decoded.get("accept"));
    }

    @Test
    public void testQpackEmptyHeaders() {
        QpackEncoder encoder = new QpackEncoder();
        QpackDecoder decoder = new QpackDecoder();

        HttpHeaders headers = new HttpHeaders();
        byte[] encoded = encoder.encode(headers);
        assertNotNull(encoded);

        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertTrue(decoded.isEmpty());
    }

    @Test
    public void testQpackLargeHeaderValue() {
        QpackEncoder encoder = new QpackEncoder();
        QpackDecoder decoder = new QpackDecoder();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++)
            sb.append("x");
        String largeValue = sb.toString();

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-large", largeValue);

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        assertEquals(largeValue, decoded.get("x-large"));
    }

    @Test
    public void testQpackManyHeaders() {
        QpackEncoder encoder = new QpackEncoder();
        QpackDecoder decoder = new QpackDecoder();

        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < 50; i++) {
            headers.add("x-header-" + i, "value-" + i);
        }

        byte[] encoded = encoder.encode(headers);
        HttpHeaders decoded = decoder.decode(encoded, 0, encoded.length);
        assertNotNull(decoded);
        for (int i = 0; i < 50; i++) {
            assertEquals("value-" + i, decoded.get("x-header-" + i));
        }
    }

    // ========================= QpackStaticTable Tests =========================

    @Test
    public void testQpackStaticTableLength() {
        assertEquals(99, QpackStaticTable.length());
    }

    @Test
    public void testQpackStaticTableEntries() {
        QpackHeaderField f0 = QpackStaticTable.get(0);
        assertEquals(":authority", f0.name());
        assertEquals("", f0.value());

        QpackHeaderField f17 = QpackStaticTable.get(17);
        assertEquals(":method", f17.name());
        assertEquals("GET", f17.value());
    }

    @Test
    public void testQpackStaticTableFindIndex() {
        int idx = QpackStaticTable.findIndex(":method", "GET");
        assertEquals(17, idx);

        int nameIdx = QpackStaticTable.findNameIndex(":authority");
        assertEquals(0, nameIdx);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testQpackStaticTableOutOfBounds() {
        QpackStaticTable.get(99);
    }

    @Test
    public void testQpackStaticTableFindNotExist() {
        int idx = QpackStaticTable.findIndex("x-nonexistent", "no-such-value");
        assertEquals(-1, idx);
    }

    @Test
    public void testQpackHeaderFieldSize() {
        QpackHeaderField field = new QpackHeaderField("content-type", "text/html");
        // size = name.length() + value.length() + 32
        assertEquals(12 + 9 + 32, field.size());
    }

    // ========================= Constants Tests =========================

    @Test
    public void testHttp3FrameTypeValues() {
        assertEquals(0x00L, Http3FrameType.DATA);
        assertEquals(0x01L, Http3FrameType.HEADERS);
        assertEquals(0x03L, Http3FrameType.CANCEL_PUSH);
        assertEquals(0x04L, Http3FrameType.SETTINGS);
        assertEquals(0x05L, Http3FrameType.PUSH_PROMISE);
        assertEquals(0x07L, Http3FrameType.GOAWAY);
        assertEquals(0x0dL, Http3FrameType.MAX_PUSH_ID);
    }

    @Test
    public void testHttp3FrameTypeNames() {
        assertEquals("DATA", Http3FrameType.name(Http3FrameType.DATA));
        assertEquals("HEADERS", Http3FrameType.name(Http3FrameType.HEADERS));
        assertEquals("CANCEL_PUSH", Http3FrameType.name(Http3FrameType.CANCEL_PUSH));
        assertEquals("SETTINGS", Http3FrameType.name(Http3FrameType.SETTINGS));
        assertTrue(Http3FrameType.name(0xFFFFL).contains("UNKNOWN"));
    }

    @Test
    public void testHttp3FrameTypeReserved() {
        // Reserved: 0x1f * N + 0x21
        assertTrue(Http3FrameType.isReserved(0x21));    // N=0
        assertTrue(Http3FrameType.isReserved(0x40));    // N=1: 0x1f + 0x21 = 0x40
        assertFalse(Http3FrameType.isReserved(Http3FrameType.DATA));
        assertFalse(Http3FrameType.isReserved(Http3FrameType.HEADERS));
        assertFalse(Http3FrameType.isReserved(0x20));
    }

    @Test
    public void testHttp3StreamStateValues() {
        Http3StreamState[] states = Http3StreamState.values();
        assertEquals(4, states.length);
        assertEquals(Http3StreamState.IDLE, Http3StreamState.valueOf("IDLE"));
        assertEquals(Http3StreamState.OPEN, Http3StreamState.valueOf("OPEN"));
        assertEquals(Http3StreamState.HALF_CLOSED, Http3StreamState.valueOf("HALF_CLOSED"));
        assertEquals(Http3StreamState.CLOSED, Http3StreamState.valueOf("CLOSED"));
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
        assertEquals(0x0200L, Http3ErrorCode.QPACK_DECOMPRESSION_FAILED);
        assertEquals(0x0201L, Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR);
        assertEquals(0x0202L, Http3ErrorCode.QPACK_DECODER_STREAM_ERROR);
    }

    @Test
    public void testHttp3ErrorCodeNames() {
        assertEquals("H3_NO_ERROR", Http3ErrorCode.name(Http3ErrorCode.H3_NO_ERROR));
        assertEquals("H3_INTERNAL_ERROR", Http3ErrorCode.name(Http3ErrorCode.H3_INTERNAL_ERROR));
        assertEquals("QPACK_DECOMPRESSION_FAILED", Http3ErrorCode.name(Http3ErrorCode.QPACK_DECOMPRESSION_FAILED));
        assertTrue(Http3ErrorCode.name(0xFFFFL).contains("UNKNOWN"));
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
    public void testHttp3SettingsSetters() {
        Http3Settings settings = new Http3Settings();
        settings.qpackMaxTableCapacity(4096);
        settings.maxFieldSectionSize(8192);
        settings.qpackBlockedStreams(100);
        settings.enableConnectProtocol(true);

        assertEquals(4096L, settings.qpackMaxTableCapacity());
        assertEquals(8192L, settings.maxFieldSectionSize());
        assertEquals(100L, settings.qpackBlockedStreams());
        assertTrue(settings.enableConnectProtocol());
    }

    @Test
    public void testHttp3SettingsCopyConstructor() {
        Http3Settings original = new Http3Settings();
        original.qpackMaxTableCapacity(2048);
        original.maxFieldSectionSize(4096);
        original.qpackBlockedStreams(50);
        original.enableConnectProtocol(true);

        Http3Settings copy = new Http3Settings(original);
        assertEquals(2048L, copy.qpackMaxTableCapacity());
        assertEquals(4096L, copy.maxFieldSectionSize());
        assertEquals(50L, copy.qpackBlockedStreams());
        assertTrue(copy.enableConnectProtocol());
    }

    @Test
    public void testHttp3SettingsApply() {
        Http3Settings settings = new Http3Settings();
        settings.applySetting(Http3Settings.SETTINGS_QPACK_MAX_TABLE_CAPACITY, 1024);
        settings.applySetting(Http3Settings.SETTINGS_MAX_FIELD_SECTION_SIZE, 2048);
        settings.applySetting(Http3Settings.SETTINGS_QPACK_BLOCKED_STREAMS, 10);
        settings.applySetting(Http3Settings.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1);

        assertEquals(1024L, settings.qpackMaxTableCapacity());
        assertEquals(2048L, settings.maxFieldSectionSize());
        assertEquals(10L, settings.qpackBlockedStreams());
        assertTrue(settings.enableConnectProtocol());
    }

    @Test
    public void testHttp3SettingsReservedIds() {
        // Reserved: 0x1f * N + 0x21
        assertTrue(Http3Settings.isReservedSetting(0x21));
        assertTrue(Http3Settings.isReservedSetting(0x40));
        assertFalse(Http3Settings.isReservedSetting(Http3Settings.SETTINGS_QPACK_MAX_TABLE_CAPACITY));
        assertFalse(Http3Settings.isReservedSetting(Http3Settings.SETTINGS_MAX_FIELD_SECTION_SIZE));
    }

    @Test
    public void testHttp3SettingsConstants() {
        assertEquals(0x01L, Http3Settings.SETTINGS_QPACK_MAX_TABLE_CAPACITY);
        assertEquals(0x06L, Http3Settings.SETTINGS_MAX_FIELD_SECTION_SIZE);
        assertEquals(0x07L, Http3Settings.SETTINGS_QPACK_BLOCKED_STREAMS);
        assertEquals(0x08L, Http3Settings.SETTINGS_ENABLE_CONNECT_PROTOCOL);
    }

    // ========================= HttpVersion Tests =========================

    @Test
    public void testHttp3VersionConstant() {
        assertNotNull(HttpVersion.HTTP_3_0);
        assertEquals("HTTP/3.0", HttpVersion.HTTP_3_0.text());
    }
}
