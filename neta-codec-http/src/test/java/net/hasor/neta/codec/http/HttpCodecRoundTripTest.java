package net.hasor.neta.codec.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * End-to-end round-trip tests: encode → decode through the pipeline.
 * Also tests {@link HttpServerDuplexe} and {@link HttpClientDuplexe}.
 */
public class HttpCodecRoundTripTest {

    // ========================= Request Round Trip: Encode → Decode =========================

    @Test
    public void testRequestRoundTrip() throws Throwable {
        NetManager neta = new NetManager();

        // Server: decodes requests
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());

        // Client: encodes requests
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Create and send request
        String body = "Hello, World!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/data", bodyBuf);
        request.headers().add("Host", "www.example.com");
        request.headers().add("Content-Type", "text/plain");
        request.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(request).get();

        // Verify decoded result
        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/api/data", decoded.uri());
        assertEquals(HttpVersion.HTTP_1_1, decoded.protocolVersion());
        assertEquals("www.example.com", decoded.headers().get("host"));
        assertEquals("text/plain", decoded.headers().get("content-type"));

        ByteBuf content = decoded.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);

        neta.shutdown();
    }

    // ========================= Response Round Trip: Encode → Decode =========================

    @Test
    public void testResponseRoundTrip() throws Throwable {
        NetManager neta = new NetManager();

        // Server: decodes responses (acts as client receiving responses)
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());

        // Client: encodes responses (acts as server sending responses)
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Create and send response
        String body = "{\"result\": 42}";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Type", "application/json");
        response.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(response).get();

        // Verify decoded result
        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(HttpStatus.OK, decoded.status());
        assertEquals(HttpVersion.HTTP_1_1, decoded.protocolVersion());
        assertEquals("application/json", decoded.headers().get("content-type"));

        ByteBuf content = decoded.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);

        neta.shutdown();
    }

    // ========================= GET Request Round Trip (no body) =========================

    @Test
    public void testGetRequestRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/page?q=test");
        request.headers().add("Host", "example.com");
        request.headers().add("Accept", "text/html");
        client.sendData(request).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/page?q=test", decoded.uri());
        assertEquals("example.com", decoded.headers().get("host"));
        assertEquals("text/html", decoded.headers().get("accept"));
        assertEquals(0, decoded.content().readableBytes());

        neta.shutdown();
    }

    // ========================= HTTP/1.0 Request Round Trip =========================

    @Test
    public void testHttp10RequestRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().add("Host", "localhost");
        client.sendData(request).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpVersion.HTTP_1_0, decoded.protocolVersion());
        assertEquals(HttpMethod.GET, decoded.method());

        neta.shutdown();
    }

    // ========================= 404 Response Round Trip =========================

    @Test
    public void test404ResponseRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Not Found";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NOT_FOUND, bodyBuf);
        response.headers().add("Content-Type", "text/plain");
        response.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(response).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(HttpStatus.NOT_FOUND, decoded.status());
        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Chunked Request Round Trip =========================

    @Test
    public void testChunkedRequestRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send head
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/stream");
        head.headers().add("Host", "example.com");
        head.headers().add("Transfer-Encoding", "chunked");
        client.sendData(head).get();

        // Send chunks
        ByteBuf chunk1 = ByteBufAllocator.DEFAULT.buffer(16);
        chunk1.writeString("Part1", StandardCharsets.US_ASCII);
        chunk1.markWriter();
        client.sendData(new DefaultHttpContent(chunk1)).get();

        ByteBuf chunk2 = ByteBufAllocator.DEFAULT.buffer(16);
        chunk2.writeString("Part2", StandardCharsets.US_ASCII);
        chunk2.markWriter();
        client.sendData(new DefaultLastHttpContent(chunk2)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/stream", decoded.uri());

        ByteBuf content = decoded.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals("Part1Part2", decodedBody);

        neta.shutdown();
    }

    // ========================= Multiple Sequential Requests (Keep-Alive) =========================

    @Test
    public void testMultipleRequestsKeepAlive() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpRequestDecoder());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // First request
        DefaultFullHttpRequest req1 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/page1");
        req1.headers().add("Host", "example.com");
        client.sendData(req1).get();

        // Second request
        DefaultFullHttpRequest req2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/page2");
        req2.headers().add("Host", "example.com");
        client.sendData(req2).get();

        assertEquals(2, rcvData.size());

        FullHttpRequest decoded1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/page1", decoded1.uri());

        FullHttpRequest decoded2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/page2", decoded2.uri());

        neta.shutdown();
    }

    // ========================= Default Model Class Tests =========================

    @Test
    public void testDefaultHttpRequestModel() {
        DefaultHttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        assertEquals(HttpVersion.HTTP_1_1, req.protocolVersion());
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/test", req.uri());
        assertNotNull(req.headers());
        assertTrue(req.headers().isEmpty());

        req.setProtocolVersion(HttpVersion.HTTP_1_0);
        assertEquals(HttpVersion.HTTP_1_0, req.protocolVersion());

        req.setMethod(HttpMethod.POST);
        assertEquals(HttpMethod.POST, req.method());

        req.setUri("/new");
        assertEquals("/new", req.uri());

        assertNotNull(req.toString());
    }

    @Test
    public void testDefaultHttpResponseModel() {
        DefaultHttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        assertEquals(HttpVersion.HTTP_1_1, resp.protocolVersion());
        assertEquals(HttpStatus.OK, resp.status());
        assertNotNull(resp.headers());

        resp.setProtocolVersion(HttpVersion.HTTP_1_0);
        assertEquals(HttpVersion.HTTP_1_0, resp.protocolVersion());

        resp.setStatus(HttpStatus.NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, resp.status());

        assertNotNull(resp.toString());
    }

    @Test
    public void testDefaultFullHttpRequestModel() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        assertEquals(HttpVersion.HTTP_1_1, req.protocolVersion());
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/", req.uri());
        assertNotNull(req.content());
        assertNotNull(req.headers());
        assertNotNull(req.trailerHeaders());
        assertNotNull(req.toString());
    }

    @Test
    public void testDefaultFullHttpResponseModel() {
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        assertEquals(HttpVersion.HTTP_1_1, resp.protocolVersion());
        assertEquals(HttpStatus.OK, resp.status());
        assertNotNull(resp.content());
        assertNotNull(resp.headers());
        assertNotNull(resp.trailerHeaders());
        assertNotNull(resp.toString());
    }

    @Test
    public void testDefaultHttpContent() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(10);
        buf.writeString("test", StandardCharsets.US_ASCII);
        buf.markWriter();
        DefaultHttpContent content = new DefaultHttpContent(buf);
        assertSame(buf, content.content());
        assertEquals(4, content.content().readableBytes());
        assertNotNull(content.toString());
    }

    @Test
    public void testDefaultLastHttpContent() {
        DefaultLastHttpContent last = new DefaultLastHttpContent();
        assertEquals(0, last.content().readableBytes());
        assertNotNull(last.trailerHeaders());
        assertTrue(last.trailerHeaders().isEmpty());
        assertNotNull(last.toString());
    }

    @Test
    public void testEmptyLastHttpContent() {
        LastHttpContent empty = DefaultLastHttpContent.EMPTY_LAST_CONTENT;
        assertEquals(0, empty.content().readableBytes());
        assertNotNull(empty.trailerHeaders());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpRequestNullVersion() {
        new DefaultHttpRequest(null, HttpMethod.GET, "/");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpRequestNullMethod() {
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, null, "/");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpRequestNullUri() {
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpResponseNullVersion() {
        new DefaultHttpResponse(null, HttpStatus.OK);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpResponseNullStatus() {
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultHttpContentNullContent() {
        new DefaultHttpContent(null);
    }
}
