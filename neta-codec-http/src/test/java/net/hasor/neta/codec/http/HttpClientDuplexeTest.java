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
 * Tests for {@link HttpClientDuplexe}.
 * <p>
 * HttpClientCodec is a bidirectional handler:
 * <ul>
 *   <li>RCV (inbound): decodes raw bytes into {@link HttpObject} (response decoding)</li>
 *   <li>SND (outbound): encodes {@link HttpObject} into raw bytes (request encoding)</li>
 * </ul>
 */
public class HttpClientDuplexeTest {

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

    // ========================= Decode 200 OK Response via ClientCodec =========================

    @Test
    public void testDecodeResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Hello from server";
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        Object msg = rcvData.poll();
        assertTrue(msg instanceof FullHttpResponse);
        FullHttpResponse decoded = (FullHttpResponse) msg;
        assertEquals(HttpStatus.OK, decoded.status());
        assertEquals(HttpVersion.HTTP_1_1, decoded.protocolVersion());
        assertEquals("text/plain", decoded.headers().get("content-type"));

        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Decode 404 Response via ClientCodec =========================

    @Test
    public void testDecode404Response() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Not Found";
        String response = "HTTP/1.1 404 Not Found\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(HttpStatus.NOT_FOUND, decoded.status());

        neta.shutdown();
    }

    // ========================= Encode GET Request via ClientCodec =========================

    @Test
    public void testEncodeGetRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/index.html");
        request.headers().add("Host", "www.example.com");
        client.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("GET /index.html HTTP/1.1\r\n"));
        assertTrue(encoded.contains("host: www.example.com\r\n"));

        neta.shutdown();
    }

    // ========================= Encode POST Request with Body via ClientCodec =========================

    @Test
    public void testEncodePostRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "payload-data";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit", bodyBuf);
        request.headers().add("Host", "api.example.com");
        request.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("POST /submit HTTP/1.1\r\n"));
        assertTrue(encoded.contains(body));

        neta.shutdown();
    }

    // ========================= Full Bidirectional: Encode Request + Decode Response =========================

    @Test
    public void testBidirectionalRequestAndResponse() throws Throwable {
        // Test encode path: ClientCodec encodes request, raw channel receives bytes
        NetManager neta1 = new NetManager();
        VrtChannel encodeChannel = (VrtChannel) neta1.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
        }, VrtSoConfig.asServer());
        VrtChannel rawReceiver = (VrtChannel) neta1.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer1 = new VrtTransfer(neta1);
        transfer1.linkTo(encodeChannel, rawReceiver, VrtTransfer.duplicate());

        Queue<Object> serverRcvData = new ArrayDeque<>();
        rawReceiver.subscribe(d -> serverRcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/status");
        request.headers().add("Host", "api.example.com");
        encodeChannel.sendData(request).get();

        assertTrue(serverRcvData.size() >= 1);
        StringBuilder reqResult = new StringBuilder();
        Object msg;
        while ((msg = serverRcvData.poll()) != null) {
            reqResult.append(readByteBuf((ByteBuf) msg));
        }
        assertTrue(reqResult.toString().contains("GET /api/status HTTP/1.1\r\n"));
        neta1.shutdown();

        // Test decode path: raw channel sends response bytes, ClientCodec decodes into HttpResponse
        NetManager neta2 = new NetManager();
        VrtChannel decodeChannel = (VrtChannel) neta2.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel rawSender = (VrtChannel) neta2.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer2 = new VrtTransfer(neta2);
        transfer2.linkTo(rawSender, decodeChannel, VrtTransfer.duplicate());

        Queue<Object> clientRcvData = new ArrayDeque<>();
        decodeChannel.subscribe(d -> clientRcvData.offer(d.getData()));

        String respBody = "{\"status\":\"up\"}";
        String rawResponse = "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + respBody.length() + "\r\n" + "\r\n" + respBody;
        rawSender.sendData(toByteBuf(rawResponse)).get();

        assertEquals(1, clientRcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) clientRcvData.poll();
        assertEquals(HttpStatus.OK, decoded.status());
        ByteBuf content = decoded.content();
        assertEquals(respBody, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta2.shutdown();
    }

    // ========================= Decode Chunked Response via ClientCodec =========================

    @Test
    public void testDecodeChunkedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\nHello\r\n" + "5\r\nWorld\r\n" + "0\r\n\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        ByteBuf content = decoded.content();
        assertEquals("HelloWorld", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Encode Chunked Request via ClientCodec =========================

    @Test
    public void testEncodeChunkedRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send request head
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        head.headers().add("Host", "example.com");
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
        assertTrue(encoded.contains("POST /upload HTTP/1.1\r\n"));
        assertTrue(encoded.contains("transfer-encoding: chunked\r\n"));
        assertTrue(encoded.contains("4\r\ndata\r\n"));
        assertTrue(encoded.contains("0\r\n"));

        neta.shutdown();
    }

    // ========================= ClientCodec with custom limits =========================

    @Test
    public void testClientCodecWithCustomLimits() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe(4096, 8192, 8192));
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Content-Length: 2\r\n" + "\r\n" + "OK";
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(200, decoded.status().code());

        neta.shutdown();
    }

    // ========================= Decode 204 No Content via ClientCodec =========================

    @Test
    public void testDecode204NoContent() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
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
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(204, decoded.status().code());
        assertEquals(0, decoded.content().readableBytes());

        neta.shutdown();
    }

    // ========================= Multiple Sequential Responses via ClientCodec =========================

    @Test
    public void testMultipleSequentialResponses() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast(new HttpClientDuplexe());
            ctx.addLastDecoder(new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send two responses sequentially
        String resp1 = "HTTP/1.1 200 OK\r\n" + "Content-Length: 2\r\n" + "\r\n" + "OK";
        String resp2 = "HTTP/1.1 201 Created\r\n" + "Content-Length: 4\r\n" + "\r\n" + "Done";
        client.sendData(toByteBuf(resp1)).get();
        client.sendData(toByteBuf(resp2)).get();

        assertEquals(2, rcvData.size());
        FullHttpResponse decoded1 = (FullHttpResponse) rcvData.poll();
        assertEquals(200, decoded1.status().code());
        FullHttpResponse decoded2 = (FullHttpResponse) rcvData.poll();
        assertEquals(201, decoded2.status().code());

        neta.shutdown();
    }
}
