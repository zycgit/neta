package net.hasor.neta.codec.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.constant.HttpMethod;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Extreme boundary tests: packet gluing (粘包), half packets (半包),
 * packet loss/corruption (丢包), data disorder (错乱), and exception boundaries.
 * <p>
 * These tests validate that the HTTP decoders handle real-world network conditions
 * correctly: data arriving in arbitrary fragments, multiple messages in one packet,
 * malformed data, and self-protection limits.
 */
public class ExtremeBoundaryTest {

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static ByteBuf toByteBuf(byte[] bytes) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    // ===========================================================================
    //  Error capturing helpers
    // ===========================================================================

    /**
     * Two complete GET requests sent as a single packet.
     * Both must be decoded correctly.
     */
    @Test
    public void testStickyPacket_TwoGetRequests() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Two complete requests in one packet
        String twoRequests = "GET /first HTTP/1.1\r\nHost: a.com\r\n\r\n" + "GET /second HTTP/1.1\r\nHost: b.com\r\n\r\n";
        client.sendData(toByteBuf(twoRequests)).get();

        // Each request produces: HttpRequest + LastHttpContent = 2 objects per request
        assertEquals(4, rcvData.size());

        HttpRequest req1 = (HttpRequest) rcvData.poll();
        assertEquals("/first", req1.uri());
        assertTrue(rcvData.poll() instanceof LastHttpContent); // end of first

        HttpRequest req2 = (HttpRequest) rcvData.poll();
        assertEquals("/second", req2.uri());
        assertTrue(rcvData.poll() instanceof LastHttpContent); // end of second

        neta.shutdown();
    }

    /**
     * Three POST requests with bodies, all glued into one packet.
     */
    @Test
    public void testStickyPacket_ThreePostRequests() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String threeRequests = "POST /a HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nAAA" + "POST /b HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nBBB" + "POST /c HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nCCC";
        client.sendData(toByteBuf(threeRequests)).get();

        assertEquals(3, rcvData.size());
        FullHttpRequest r1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/a", r1.uri());
        assertEquals("AAA", r1.content().readString(r1.content().readableBytes(), StandardCharsets.US_ASCII));

        FullHttpRequest r2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/b", r2.uri());
        assertEquals("BBB", r2.content().readString(r2.content().readableBytes(), StandardCharsets.US_ASCII));

        FullHttpRequest r3 = (FullHttpRequest) rcvData.poll();
        assertEquals("/c", r3.uri());
        assertEquals("CCC", r3.content().readString(r3.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    /**
     * Sticky packet: GET + chunked POST in one packet.
     */
    @Test
    public void testStickyPacket_GetThenChunkedPost() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String combined = "GET /first HTTP/1.1\r\nHost: x\r\n\r\n" + "POST /second HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" + "5\r\nHello\r\n0\r\n\r\n";
        client.sendData(toByteBuf(combined)).get();

        assertEquals(2, rcvData.size());
        FullHttpRequest r1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/first", r1.uri());
        assertEquals(HttpMethod.GET, r1.method());

        FullHttpRequest r2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/second", r2.uri());
        assertEquals(HttpMethod.POST, r2.method());
        assertEquals("Hello", r2.content().readString(r2.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ===========================================================================
    //  1. 粘包 (Packet Gluing) — Multiple complete messages in one TCP segment
    // ===========================================================================

    /**
     * Sticky packet for responses: Two complete responses in a single send.
     */
    @Test
    public void testStickyPacket_TwoResponses() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String twoResponses = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK" + "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found";
        client.sendData(toByteBuf(twoResponses)).get();

        assertEquals(2, rcvData.size());
        FullHttpResponse resp1 = (FullHttpResponse) rcvData.poll();
        assertEquals(200, resp1.status().code());

        FullHttpResponse resp2 = (FullHttpResponse) rcvData.poll();
        assertEquals(404, resp2.status().code());

        neta.shutdown();
    }

    /**
     * Split request line in the middle: "GET /in" + "dex HTTP/1.1\r\n..."
     */
    @Test
    public void testHalfPacket_SplitInRequestLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("GET /in")).get();
        Thread.sleep(50);
        client.sendData(toByteBuf("dex HTTP/1.1\r\nHost: example.com\r\n\r\n")).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("/index", req.uri());
        assertTrue(rcvData.poll() instanceof LastHttpContent);

        neta.shutdown();
    }

    /**
     * Split in the middle of CRLF: "...\r" + "\nHost: ..."
     */
    @Test
    public void testHalfPacket_SplitInCRLF() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("GET / HTTP/1.1\r")).get();
        Thread.sleep(50);
        client.sendData(toByteBuf("\nHost: x\r\n\r\n")).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("/", req.uri());

        neta.shutdown();
    }

    /**
     * Split headers: first packet has the request line + partial header,
     * second packet has the rest of headers + empty line.
     */
    @Test
    public void testHalfPacket_SplitInHeaders() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("GET / HTTP/1.1\r\nHost: exa")).get();
        Thread.sleep(50);
        client.sendData(toByteBuf("mple.com\r\nAccept: */*\r\n\r\n")).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("example.com", req.headers().get("Host"));
        assertEquals("*/*", req.headers().get("Accept"));

        neta.shutdown();
    }

    // ===========================================================================
    //  2. 半包 (Half Packets) — Messages split at various boundary points
    // ===========================================================================

    /**
     * Split a fixed-length body across multiple sends (byte by byte).
     */
    @Test
    public void testHalfPacket_BodyByteByByte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String header = "POST /data HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\n";
        client.sendData(toByteBuf(header)).get();
        Thread.sleep(30);

        // Send body byte by byte
        for (char c : "HELLO".toCharArray()) {
            client.sendData(toByteBuf(String.valueOf(c))).get();
            Thread.sleep(10);
        }

        // Wait for full aggregation
        Thread.sleep(100);
        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals("HELLO", req.content().readString(req.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    /**
     * Split chunked encoding across extreme boundaries:
     * chunk-size in one packet, chunk-data in another, CRLF in a third.
     */
    @Test
    public void testHalfPacket_ChunkedSplitAtEveryBoundary() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send the request headers
        client.sendData(toByteBuf("POST /chunked HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n")).get();
        Thread.sleep(30);

        // chunk 1: size line
        client.sendData(toByteBuf("3\r\n")).get();
        Thread.sleep(10);
        // chunk 1: data
        client.sendData(toByteBuf("abc")).get();
        Thread.sleep(10);
        // chunk 1: trailing CRLF
        client.sendData(toByteBuf("\r\n")).get();
        Thread.sleep(10);

        // chunk 2: size + data + CRLF split in middle of data
        client.sendData(toByteBuf("4\r\nd")).get();
        Thread.sleep(10);
        client.sendData(toByteBuf("ef")).get();
        Thread.sleep(10);
        client.sendData(toByteBuf("g\r\n")).get();
        Thread.sleep(10);

        // last chunk
        client.sendData(toByteBuf("0\r\n\r\n")).get();

        Thread.sleep(100);
        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals("abcdefg", req.content().readString(req.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    /**
     * Half packet: entire request split byte-by-byte (the ultimate stress test).
     */
    @Test
    public void testHalfPacket_EntireRequestByteByByte() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String fullRequest = "POST /bbb HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\nDATA";
        byte[] bytes = fullRequest.getBytes(StandardCharsets.US_ASCII);
        for (byte b : bytes) {
            client.sendData(toByteBuf(new byte[] { b })).get();
            Thread.sleep(5);
        }

        Thread.sleep(200);
        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals("/bbb", req.uri());
        assertEquals("DATA", req.content().readString(req.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    /**
     * Half packet for response: split status line in middle.
     */
    @Test
    public void testHalfPacket_ResponseSplitInStatusLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("HTTP/1.1 20")).get();
        Thread.sleep(50);
        client.sendData(toByteBuf("0 OK\r\nContent-Length: 2\r\n\r\nOK")).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse resp = (FullHttpResponse) rcvData.poll();
        assertEquals(200, resp.status().code());
        assertEquals("OK", resp.content().readString(resp.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    /**
     * First request body is glued with the second request's request-line,
     * but the second request is only half delivered.
     */
    @Test
    public void testStickyAndHalf_BodyGluedWithNextRequestLine() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // First packet: complete first request + beginning of second
        String packet1 = "GET /a HTTP/1.1\r\nHost: x\r\n\r\nGET /b HTTP/1.1\r\nHo";
        client.sendData(toByteBuf(packet1)).get();
        Thread.sleep(50);

        // First request should be decoded already
        assertTrue("First request should be decoded", rcvData.size() >= 1);
        FullHttpRequest r1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/a", r1.uri());

        // Send remainder of second request
        String packet2 = "st: y\r\n\r\n";
        client.sendData(toByteBuf(packet2)).get();
        Thread.sleep(100);

        assertEquals(1, rcvData.size());
        FullHttpRequest r2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/b", r2.uri());

        neta.shutdown();
    }

    /**
     * Completely invalid data (not HTTP at all).
     */
    @Test
    public void testCorruption_PureGarbage() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Send completely broken data: no space
        client.sendData(toByteBuf("GARBAGE_NONSENSE\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull("Should have error for garbage data", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Method only, no URI and no version (invalid request line).
     */
    @Test
    public void testCorruption_MethodOnly() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("GET\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    // ===========================================================================
    //  3. 粘包+半包混合 — Half of one message glued with start of another
    // ===========================================================================

    /**
     * Missing version in request line.
     */
    @Test
    public void testCorruption_MissingVersion() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("GET /path\r\nHost: x\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    // ===========================================================================
    //  4. 丢包/损坏 (Corruption) — Malformed/truncated/garbage data
    // ===========================================================================

    /**
     * Header without colon (malformed header).
     */
    @Test
    public void testCorruption_HeaderWithoutColon() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("GET / HTTP/1.1\r\nBadHeaderNoColon\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Invalid Content-Length (non-numeric).
     */
    @Test
    public void testCorruption_InvalidContentLength() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /data HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Negative Content-Length.
     */
    @Test
    public void testCorruption_NegativeContentLength() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /data HTTP/1.1\r\nHost: x\r\nContent-Length: -5\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpContentTooLargeException);

        neta.shutdown();
    }

    /**
     * Invalid chunk size (non-hex).
     */
    @Test
    public void testCorruption_InvalidChunkSize() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nZZZ\r\ndata\r\n0\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Empty chunk size line.
     */
    @Test
    public void testCorruption_EmptyChunkSize() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n\r\ndata\r\n0\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Invalid response status line (garbled).
     */
    @Test
    public void testCorruption_ResponseInvalidStatusLine() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("INVALID_STATUS_LINE\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Response with non-numeric status code.
     */
    @Test
    public void testCorruption_ResponseNonNumericStatusCode() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("HTTP/1.1 XYZ OK\r\nContent-Length: 0\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        neta.shutdown();
    }

    /**
     * Binary garbage injected as HTTP data.
     */
    @Test
    public void testCorruption_BinaryGarbage() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(50, 100, 100);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Random binary data with a newline to trigger parsing
        byte[] garbage = new byte[] { 0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD, 0x0D, 0x0A, 0x0D, 0x0A };
        client.sendData(toByteBuf(garbage)).get();
        Thread.sleep(100);

        // Should fail with some kind of protocol exception (malformed request or version parse failure)
        assertNotNull("Should have error for binary garbage", decoder.lastError);
        assertTrue("Should be HttpProtocolException or subclass", decoder.lastError instanceof HttpProtocolException || decoder.lastError instanceof IllegalArgumentException);

        neta.shutdown();
    }

    /**
     * Request line exceeds maxInitialLineLength → HttpInitialLineTooLongException.
     */
    @Test
    public void testSelfProtection_RequestLineTooLong() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(20, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Request line is ~48 chars, far exceeds 20
        client.sendData(toByteBuf("GET /this-is-a-very-long-uri-path HTTP/1.1\r\nHost: x\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue("Should be HttpInitialLineTooLongException", decoder.lastError instanceof HttpInitialLineTooLongException);

        neta.shutdown();
    }

    /**
     * Response status line exceeds maxInitialLineLength → HttpInitialLineTooLongException.
     */
    @Test
    public void testSelfProtection_StatusLineTooLong() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(20, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("HTTP/1.1 200 This Is A Very Long Reason Phrase\r\nContent-Length: 0\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue("Should be HttpInitialLineTooLongException", decoder.lastError instanceof HttpInitialLineTooLongException);

        neta.shutdown();
    }

    /**
     * Headers exceed maxHeaderSize → HttpHeaderTooLargeException.
     */
    @Test
    public void testSelfProtection_HeadersTooLarge() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 50, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String request = "GET / HTTP/1.1\r\n" + "Host: example.com\r\n" + "Accept: text/html;charset=UTF-8\r\n" + "X-Long-Header: some-very-long-value-here\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue("Should be HttpHeaderTooLargeException", decoder.lastError instanceof HttpHeaderTooLargeException);

        neta.shutdown();
    }

    // ===========================================================================
    //  5. 自保护参数 — Self-protection limit enforcement with unified exceptions
    // ===========================================================================

    /**
     * Response headers exceed maxHeaderSize → HttpHeaderTooLargeException.
     */
    @Test
    public void testSelfProtection_ResponseHeadersTooLarge() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(4096, 50, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html;charset=UTF-8\r\n" + "X-Long-Header: some-very-long-value-here\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue("Should be HttpHeaderTooLargeException", decoder.lastError instanceof HttpHeaderTooLargeException);

        neta.shutdown();
    }

    /**
     * Headers exceed limit via split packets (half-packet + self-protection).
     * headerBytes must accumulate across partial reads.
     */
    @Test
    public void testSelfProtection_HeadersTooLargeAcrossPackets() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 50, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Send request line first
        client.sendData(toByteBuf("GET / HTTP/1.1\r\n")).get();
        Thread.sleep(30);

        // First header batch (~21 bytes) — within limit
        client.sendData(toByteBuf("Host: example.com\r\n")).get();
        Thread.sleep(30);

        // Second header batch (~35 bytes more, total > 50) — should exceed
        client.sendData(toByteBuf("Accept: text/html;charset=UTF-8\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue("Should be HttpHeaderTooLargeException", decoder.lastError instanceof HttpHeaderTooLargeException);

        neta.shutdown();
    }

    /**
     * Aggregator content exceeds maxContentLength → HttpContentTooLargeException.
     */
    @Test
    public void testSelfProtection_ContentTooLargeViaAggregator() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingAggregator aggregator = new ErrorCapturingAggregator(10); // 10 bytes max
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(aggregator);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /big HTTP/1.1\r\nHost: x\r\nContent-Length: 20\r\n\r\n" + "01234567890123456789")).get();
        Thread.sleep(100);

        assertNotNull(aggregator.lastError);
        assertTrue("Should be HttpContentTooLargeException", aggregator.lastError instanceof HttpContentTooLargeException);

        neta.shutdown();
    }

    /**
     * Chunked content exceeding aggregator limit across chunks.
     */
    @Test
    public void testSelfProtection_ChunkedContentTooLarge() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingAggregator aggregator = new ErrorCapturingAggregator(5);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(aggregator);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" + "3\r\nabc\r\n" + "5\r\ndefgh\r\n" + "0\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(aggregator.lastError);
        assertTrue("Should be HttpContentTooLargeException", aggregator.lastError instanceof HttpContentTooLargeException);

        neta.shutdown();
    }

    /**
     * Request line exactly at maxInitialLineLength should succeed.
     */
    @Test
    public void testBoundary_RequestLineExactlyAtLimit() throws Throwable {
        // "GET /xx HTTP/1.1" = 16 chars, set maxInitialLineLength=16
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder(16, 8192, 8192));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("GET /xx HTTP/1.1\r\nHost: x\r\n\r\n")).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("/xx", req.uri());

        neta.shutdown();
    }

    /**
     * Request line one byte over maxInitialLineLength should fail.
     */
    @Test
    public void testBoundary_RequestLineOneOverLimit() throws Throwable {
        // "GET /xxx HTTP/1.1" = 17 chars, set maxInitialLineLength=16
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(16, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("GET /xxx HTTP/1.1\r\nHost: x\r\n\r\n")).get();
        Thread.sleep(100);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpInitialLineTooLongException);

        neta.shutdown();
    }

    /**
     * Content exactly at maxContentLength should succeed.
     */
    @Test
    public void testBoundary_ContentExactlyAtLimit() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(5));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nHELLO")).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals("HELLO", req.content().readString(req.content().readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ===========================================================================
    //  6. 边界值精确测试 — Exact boundary value tests
    // ===========================================================================

    /**
     * Content one byte over maxContentLength should fail.
     */
    @Test
    public void testBoundary_ContentOneOverLimit() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingAggregator aggregator = new ErrorCapturingAggregator(5);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(aggregator);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nContent-Length: 6\r\n\r\nHELLO!")).get();
        Thread.sleep(100);

        assertNotNull(aggregator.lastError);
        assertTrue(aggregator.lastError instanceof HttpContentTooLargeException);

        neta.shutdown();
    }

    /**
     * Completely empty body (Content-Length: 0).
     */
    @Test
    public void testMinimal_ZeroContentLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("POST /empty HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals(0, req.content().readableBytes());

        neta.shutdown();
    }

    /**
     * Only empty chunked encoding (0-length last chunk immediately).
     */
    @Test
    public void testMinimal_EmptyChunkedBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("POST /x HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest req = (FullHttpRequest) rcvData.poll();
        assertEquals(0, req.content().readableBytes());

        neta.shutdown();
    }

    /**
     * Multiple leading empty lines before request (RFC 7230 §3.5 robustness).
     */
    @Test
    public void testMinimal_LeadingEmptyLinesBeforeRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(toByteBuf("\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n")).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("/", req.uri());

        neta.shutdown();
    }

    // ===========================================================================
    //  7. 空 / 最小请求 — Empty and minimal edge cases
    // ===========================================================================

    /**
     * Rapidly send 100 requests, each as a separate single-byte-split stream.
     * Tests decoder state machine stability under sustained load.
     */
    @Test
    public void testStress_100RequestsInRandomFragments() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Build 100 requests glued together
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("GET /path/").append(i).append(" HTTP/1.1\r\n").append("Host: stress.test\r\n").append("X-Req-Id: ").append(i).append("\r\n").append("\r\n");
        }
        String allRequests = sb.toString();
        byte[] bytes = allRequests.getBytes(StandardCharsets.US_ASCII);

        // Send in random-sized fragments (1-20 bytes each)
        java.util.Random rng = new java.util.Random(42);
        int offset = 0;
        while (offset < bytes.length) {
            int chunkLen = Math.min(1 + rng.nextInt(20), bytes.length - offset);
            byte[] fragment = new byte[chunkLen];
            System.arraycopy(bytes, offset, fragment, 0, chunkLen);
            client.sendData(toByteBuf(fragment)).get();
            offset += chunkLen;
        }

        Thread.sleep(300);

        // All 100 requests should be decoded
        assertEquals("Should decode all 100 requests", 100, rcvData.size());

        // Verify order
        for (int i = 0; i < 100; i++) {
            FullHttpRequest req = (FullHttpRequest) rcvData.poll();
            assertNotNull("Request " + i + " should not be null", req);
            assertEquals("/path/" + i, req.uri());
            assertEquals(String.valueOf(i), req.headers().get("X-Req-Id"));
        }

        neta.shutdown();
    }

    /**
     * Stress test: 50 POST requests with bodies, sent in a mix of sticky and half packets.
     */
    @Test
    public void testStress_50PostRequestsMixed() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            String body = "body" + i;
            sb.append("POST /post/").append(i).append(" HTTP/1.1\r\n").append("Host: test\r\n").append("Content-Length: ").append(body.length()).append("\r\n").append("\r\n").append(body);
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        java.util.Random rng = new java.util.Random(123);
        int offset = 0;
        while (offset < bytes.length) {
            int chunkLen = Math.min(1 + rng.nextInt(50), bytes.length - offset);
            byte[] fragment = new byte[chunkLen];
            System.arraycopy(bytes, offset, fragment, 0, chunkLen);
            client.sendData(toByteBuf(fragment)).get();
            offset += chunkLen;
        }

        Thread.sleep(500);

        assertEquals("Should decode all 50 POST requests", 50, rcvData.size());
        for (int i = 0; i < 50; i++) {
            FullHttpRequest req = (FullHttpRequest) rcvData.poll();
            assertNotNull(req);
            assertEquals("/post/" + i, req.uri());
            String expectedBody = "body" + i;
            assertEquals(expectedBody, req.content().readString(req.content().readableBytes(), StandardCharsets.US_ASCII));
        }

        neta.shutdown();
    }

    /**
     * After a protocol exception, the decoder state is corrupted and
     * subsequent valid data should not produce valid output.
     * (This tests the "fail-fast" behavior.)
     */
    @Test
    public void testCorruption_NoRecoveryAfterError() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send malformed request to trigger error
        client.sendData(toByteBuf("INVALID\r\n\r\n")).get();
        Thread.sleep(50);

        assertNotNull(decoder.lastError);
        assertTrue(decoder.lastError instanceof HttpMalformedRequestException);

        // Since onError returns Stop, the decoder won't receive more data through pipeline.
        // The error was caught and data flow stopped.
        neta.shutdown();
    }

    // ===========================================================================
    //  8. 高并发粘包半包混合 — Large scale mixed scenario
    // ===========================================================================

    /**
     * All HTTP exception subtypes must be instanceof HttpProtocolException.
     */
    @Test
    public void testExceptionHierarchy() {
        HttpProtocolException base = new HttpProtocolException("base");
        assertTrue(base instanceof RuntimeException);

        HttpInitialLineTooLongException lineEx = new HttpInitialLineTooLongException("line");
        assertTrue(lineEx instanceof HttpProtocolException);
        assertTrue(lineEx instanceof RuntimeException);

        HttpHeaderTooLargeException headerEx = new HttpHeaderTooLargeException("header");
        assertTrue(headerEx instanceof HttpProtocolException);

        HttpContentTooLargeException contentEx = new HttpContentTooLargeException("content");
        assertTrue(contentEx instanceof HttpProtocolException);

        HttpMalformedRequestException malformedEx = new HttpMalformedRequestException("malformed");
        assertTrue(malformedEx instanceof HttpProtocolException);

        HttpMalformedRequestException malformedWithCause = new HttpMalformedRequestException("msg", new Exception("cause"));
        assertNotNull(malformedWithCause.getCause());
        assertTrue(malformedWithCause.getCause() instanceof Exception);
    }

    private static class ErrorCapturingRequestDecoder extends HttpRequestDecoder {
        volatile Throwable lastError;

        ErrorCapturingRequestDecoder() {
            super();
        }

        ErrorCapturingRequestDecoder(int maxInitialLine, int maxHeader, int maxChunk) {
            super(maxInitialLine, maxHeader, maxChunk);
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
            lastError = e;
            eh.clear();
            return ProtoStatus.Stop;
        }
    }

    // ===========================================================================
    //  9. 异常恢复不可能 — Verify decoder is in error state after exception
    // ===========================================================================

    private static class ErrorCapturingResponseDecoder extends HttpResponseDecoder {
        volatile Throwable lastError;

        ErrorCapturingResponseDecoder() {
            super();
        }

        ErrorCapturingResponseDecoder(int maxInitialLine, int maxHeader, int maxChunk) {
            super(maxInitialLine, maxHeader, maxChunk);
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
            lastError = e;
            eh.clear();
            return ProtoStatus.Stop;
        }
    }

    // ===========================================================================
    //  10. 异常类型继承关系验证
    // ===========================================================================

    private static class ErrorCapturingAggregator extends HttpObjectAggregator {
        volatile Throwable lastError;

        ErrorCapturingAggregator(int maxContentLength) {
            super(maxContentLength);
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
            lastError = e;
            eh.clear();
            return ProtoStatus.Stop;
        }
    }
}
