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
 * Tests for {@link HttpServerDuplexe}.
 * <p>
 * HttpServerCodec is a bidirectional handler:
 * <ul>
 *   <li>RCV (inbound): decodes raw bytes into {@link HttpObject} (request decoding)</li>
 *   <li>SND (outbound): encodes {@link HttpObject} into raw bytes (response encoding)</li>
 * </ul>
 */
public class HttpServerDuplexeTest {

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static String readByteBuf(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII);
    }

    // ========================= Decode GET Request via ServerCodec =========================

    @Test
    public void testDecodeGetRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
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

        assertEquals(1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue(msg instanceof FullHttpRequest);
        FullHttpRequest decoded = (FullHttpRequest) msg;
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/index.html", decoded.uri());
        assertEquals(HttpVersion.HTTP_1_1, decoded.protocolVersion());
        assertEquals("www.example.com", decoded.headers().get("host"));
        assertEquals(0, decoded.content().readableBytes());

        neta.shutdown();
    }

    // ========================= Decode POST Request with body =========================

    @Test
    public void testDecodePostRequestWithBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "{\"key\":\"value\"}";
        String request = "POST /api/data HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/api/data", decoded.uri());

        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Encode 200 OK Response via ServerCodec =========================

    @Test
    public void testEncodeResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Hello, World!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Type", "text/plain");
        response.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue(encoded.contains("content-type: text/plain\r\n"));
        assertTrue(encoded.contains(body));

        neta.shutdown();
    }

    // ========================= Full Bidirectional: Decode Request then Encode Response =========================

    @Test
    public void testBidirectionalRequestAndResponse() throws Throwable {
        NetManager neta = new NetManager();

        // Server side: uses HttpServerCodec for both decode inbound and encode outbound
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());

        // Client side: raw bytes
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcvData = new ArrayDeque<>();
        server.subscribe(d -> serverRcvData.offer(d.getData()));
        Queue<Object> clientRcvData = new ArrayDeque<>();
        client.subscribe(d -> clientRcvData.offer(d.getData()));

        // 1. Client sends raw HTTP request bytes to server
        String requestBody = "test-data";
        String rawRequest = "POST /api HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Length: " + requestBody.length() + "\r\n" + "\r\n" + requestBody;
        client.sendData(toByteBuf(rawRequest)).get();

        // 2. Verify server decoded the request
        assertEquals(1, serverRcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) serverRcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/api", decoded.uri());

        // 3. Server sends an HTTP response back (encoded via ServerCodec)
        String responseBody = "OK";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(responseBody.length());
        bodyBuf.writeString(responseBody, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Length", String.valueOf(responseBody.length()));
        server.sendData(response).get();

        // 4. Verify client received raw encoded response bytes
        assertTrue(clientRcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = clientRcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encodedResp = result.toString();
        assertTrue(encodedResp.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue(encodedResp.contains(responseBody));

        neta.shutdown();
    }

    // ========================= Decode Chunked Request via ServerCodec =========================

    @Test
    public void testDecodeChunkedRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String request = "POST /upload HTTP/1.1\r\n" + "Host: example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\nHello\r\n" + "6\r\n World\r\n" + "0\r\n\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());

        ByteBuf content = decoded.content();
        assertEquals("Hello World", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Encode Chunked Response via ServerCodec =========================

    @Test
    public void testEncodeChunkedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send response head
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Transfer-Encoding", "chunked");
        client.sendData(head).get();

        // Send chunk
        ByteBuf chunkBuf = ByteBufAllocator.DEFAULT.buffer(16);
        chunkBuf.writeString("data", StandardCharsets.US_ASCII);
        chunkBuf.markWriter();
        client.sendData(new DefaultHttpContent(chunkBuf)).get();

        // Send last chunk
        client.sendData(new DefaultLastHttpContent()).get();

        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue(encoded.contains("transfer-encoding: chunked\r\n"));
        assertTrue(encoded.contains("4\r\ndata\r\n"));
        assertTrue(encoded.contains("0\r\n"));

        neta.shutdown();
    }

    // ========================= ServerCodec with custom limits =========================

    @Test
    public void testServerCodecWithCustomLimits() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe(4096, 8192, 8192));
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String request = "GET /test HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/test", decoded.uri());

        neta.shutdown();
    }

    // ========================= Multiple Requests Through ServerCodec =========================

    @Test
    public void testMultipleSequentialRequests() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send two requests on the same connection (HTTP keep-alive)
        String req1 = "GET /first HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
        String req2 = "GET /second HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(req1)).get();
        client.sendData(toByteBuf(req2)).get();

        assertEquals(2, rcvData.size());
        FullHttpRequest decoded1 = (FullHttpRequest) rcvData.poll();
        assertEquals("/first", decoded1.uri());
        FullHttpRequest decoded2 = (FullHttpRequest) rcvData.poll();
        assertEquals("/second", decoded2.uri());

        neta.shutdown();
    }

    // ========================= Decode HTTP/1.0 Request via ServerCodec =========================

    @Test
    public void testDecodeHttp10Request() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String request = "GET / HTTP/1.0\r\n" + "Host: localhost\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpVersion.HTTP_1_0, decoded.protocolVersion());
        assertEquals(HttpMethod.GET, decoded.method());

        neta.shutdown();
    }

    // ========================= Encode 404 via ServerCodec =========================

    @Test
    public void testEncode404Response() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpServerDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NOT_FOUND);
        response.headers().add("Content-Length", "0");
        client.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HTTP/1.1 404 Not Found\r\n"));

        neta.shutdown();
    }
}
