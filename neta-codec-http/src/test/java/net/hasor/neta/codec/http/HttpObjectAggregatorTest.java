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
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HttpObjectAggregator}.
 */
public class HttpObjectAggregatorTest {

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    // ========================= Constructor Validation =========================

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxContentLength() {
        new HttpObjectAggregator(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxContentLength() {
        new HttpObjectAggregator(-1);
    }

    @Test
    public void testDefaultConstructor() {
        // Should not throw
        new HttpObjectAggregator();
    }

    // ========================= Aggregate GET Request (no body) =========================

    @Test
    public void testAggregateGetRequest() throws Throwable {
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

        String request = "GET /index.html HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals("should aggregate into 1 FullHttpRequest", 1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue("should be FullHttpRequest", msg instanceof FullHttpRequest);

        FullHttpRequest fullReq = (FullHttpRequest) msg;
        assertEquals(HttpMethod.GET, fullReq.method());
        assertEquals("/index.html", fullReq.uri());
        assertEquals(HttpVersion.HTTP_1_1, fullReq.protocolVersion());
        assertEquals("www.example.com", fullReq.headers().get("host"));
        assertEquals(0, fullReq.content().readableBytes());

        neta.shutdown();
    }

    // ========================= Aggregate POST Request with Body =========================

    @Test
    public void testAggregatePostRequestWithBody() throws Throwable {
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

        String body = "username=admin&password=secret";
        String request = "POST /login HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, fullReq.method());
        assertEquals("/login", fullReq.uri());

        ByteBuf content = fullReq.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);

        neta.shutdown();
    }

    // ========================= Aggregate Chunked Request =========================

    @Test
    public void testAggregateChunkedRequest() throws Throwable {
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

        String request = "POST /upload HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\n" + "Hello\r\n" + "6\r\n" + " World\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        ByteBuf content = fullReq.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals("Hello World", decodedBody);

        neta.shutdown();
    }

    // ========================= Aggregate Response =========================

    @Test
    public void testAggregateResponse() throws Throwable {
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

        String body = "{\"status\":\"ok\"}";
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue(msg instanceof FullHttpResponse);

        FullHttpResponse fullResp = (FullHttpResponse) msg;
        assertEquals(HttpStatus.OK, fullResp.status());
        assertEquals(HttpVersion.HTTP_1_1, fullResp.protocolVersion());
        assertEquals("application/json", fullResp.headers().get("content-type"));

        ByteBuf content = fullResp.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);

        neta.shutdown();
    }

    // ========================= Aggregate 204 No Content =========================

    @Test
    public void testAggregate204NoContent() throws Throwable {
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

        String response = "HTTP/1.1 204 No Content\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse fullResp = (FullHttpResponse) rcvData.poll();
        assertEquals(204, fullResp.status().code());
        assertEquals(0, fullResp.content().readableBytes());

        neta.shutdown();
    }

    // ========================= Aggregate Chunked with Trailing Headers =========================

    @Test
    public void testAggregateChunkedWithTrailingHeaders() throws Throwable {
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

        String request = "POST /data HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "Trailer: Checksum\r\n" + "\r\n" + "4\r\n" + "Test\r\n" + "0\r\n" + "Checksum: abc123\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        ByteBuf content = fullReq.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals("Test", decodedBody);
        assertEquals("abc123", fullReq.trailerHeaders().get("checksum"));

        neta.shutdown();
    }

    // ========================= FullHttpRequest passes through aggregator unchanged =========================

    @Test
    public void testFullHttpRequestPassThrough() throws Throwable {
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

        // Send a FullHttpRequest - it will be encoded, decoded, then aggregated (pass thru since it ends immediately)
        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/pass");
        fullReq.headers().add("Host", "example.com");
        client.sendData(fullReq).get();

        assertEquals(1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue(msg instanceof FullHttpRequest);
        assertEquals("/pass", ((FullHttpRequest) msg).uri());

        neta.shutdown();
    }

    // ========================= FullHttpResponse passes through aggregator unchanged =========================

    @Test
    public void testFullHttpResponsePassThrough() throws Throwable {
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

        DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        fullResp.headers().add("Content-Length", "0");
        client.sendData(fullResp).get();

        assertEquals(1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue(msg instanceof FullHttpResponse);
        assertEquals(HttpStatus.OK, ((FullHttpResponse) msg).status());

        neta.shutdown();
    }

    // ========================= Content-Length auto-set on aggregated request =========================

    @Test
    public void testContentLengthAutoSetOnAggregatedRequest() throws Throwable {
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

        String body = "abcdef";
        String request = "POST /test HTTP/1.1\r\n" + "Host: example.com\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        // Aggregator should auto-set Content-Length
        assertEquals(String.valueOf(body.length()), fullReq.headers().get("content-length"));

        neta.shutdown();
    }

    // ========================= Transfer-Encoding removed on aggregated chunked request =========================

    @Test
    public void testTransferEncodingRemovedOnAggregation() throws Throwable {
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

        String request = "POST /upload HTTP/1.1\r\n" + "Host: example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\nHello\r\n" + "0\r\n\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        // Transfer-Encoding should be removed after aggregation
        assertTrue("Transfer-Encoding should be removed", fullReq.headers().get("transfer-encoding") == null);
        // Content-Length should be set
        assertEquals("5", fullReq.headers().get("content-length"));

        neta.shutdown();
    }

    // ========================= Aggregate Chunked Response =========================

    @Test
    public void testAggregateChunkedResponse() throws Throwable {
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

        String response = "HTTP/1.1 200 OK\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "7\r\nChunk-1\r\n" + "7\r\nChunk-2\r\n" + "0\r\n\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse fullResp = (FullHttpResponse) rcvData.poll();
        ByteBuf content = fullResp.content();
        assertEquals("Chunk-1Chunk-2", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
        // Transfer-Encoding should be removed
        assertTrue(fullResp.headers().get("transfer-encoding") == null);
        // Content-Length should be auto-set
        assertEquals("14", fullResp.headers().get("content-length"));

        neta.shutdown();
    }

    // ========================= Aggregate zero-body GET sets Content-Length: 0 =========================

    @Test
    public void testAggregateGetSetsContentLengthZero() throws Throwable {
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

        String request = "GET / HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest fullReq = (FullHttpRequest) rcvData.poll();
        assertEquals("0", fullReq.headers().get("content-length"));
        assertEquals(0, fullReq.content().readableBytes());

        neta.shutdown();
    }

    // ========================= Multiple aggregated messages sequentially =========================

    @Test
    public void testMultipleAggregatedMessages() throws Throwable {
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

        // Send two requests
        String req1 = "GET /first HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
        String req2 = "POST /second HTTP/1.1\r\n" + "Host: example.com\r\n" + "Content-Length: 4\r\n" + "\r\n" + "data";
        client.sendData(toByteBuf(req1)).get();
        client.sendData(toByteBuf(req2)).get();

        assertEquals(2, rcvData.size());
        FullHttpRequest decoded1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/first", decoded1.uri());
        FullHttpRequest decoded2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/second", decoded2.uri());
        ByteBuf content = decoded2.content();
        assertEquals("data", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }
}
