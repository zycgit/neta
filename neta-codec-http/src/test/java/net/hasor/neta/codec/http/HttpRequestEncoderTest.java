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
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link HttpRequestEncoder}.
 */
public class HttpRequestEncoderTest {

    private static String readByteBuf(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII);
    }

    // ========================= Encode Simple GET Request =========================

    @Test
    public void testEncodeSimpleGetRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/index.html");
        request.headers().add("Host", "www.example.com");
        client.sendData(request).get();

        assertTrue("should receive ByteBuf(s)", rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            assertTrue("expected ByteBuf, got " + msg.getClass().getName(), msg instanceof ByteBuf);
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("should contain request line", encoded.contains("GET /index.html HTTP/1.1\r\n"));
        assertTrue("should contain Host header", encoded.contains("host: www.example.com\r\n"));
        assertTrue("should end with CRLF CRLF", encoded.contains("\r\n\r\n"));

        neta.shutdown();
    }

    // ========================= Encode POST Request with Body =========================

    @Test
    public void testEncodePostRequestWithBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "name=value";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/submit", bodyBuf);
        request.headers().add("Host", "www.example.com");
        request.headers().add("Content-Length", String.valueOf(body.length()));
        request.headers().add("Content-Type", "application/x-www-form-urlencoded");
        client.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("should contain request line", encoded.contains("POST /submit HTTP/1.1\r\n"));
        assertTrue("should contain body", encoded.contains(body));

        neta.shutdown();
    }

    // ========================= Encode Streamed Chunked Request =========================

    @Test
    public void testEncodeStreamedChunkedRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send HttpRequest head
        DefaultHttpRequest head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        head.headers().add("Host", "www.example.com");
        head.headers().add("Transfer-Encoding", "chunked");
        client.sendData(head).get();

        // Send chunk 1
        ByteBuf chunk1Buf = ByteBufAllocator.DEFAULT.buffer(16);
        chunk1Buf.writeString("Hello", StandardCharsets.US_ASCII);
        chunk1Buf.markWriter();
        client.sendData(new DefaultHttpContent(chunk1Buf)).get();

        // Send chunk 2 as last content
        ByteBuf chunk2Buf = ByteBufAllocator.DEFAULT.buffer(16);
        chunk2Buf.writeString("World", StandardCharsets.US_ASCII);
        chunk2Buf.markWriter();
        client.sendData(new DefaultLastHttpContent(chunk2Buf)).get();

        // Collect all output
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("should contain request line", encoded.contains("POST /upload HTTP/1.1\r\n"));
        assertTrue("should contain Transfer-Encoding header", encoded.contains("transfer-encoding: chunked\r\n"));
        // Should contain chunk size "5" for "Hello"
        assertTrue("should contain chunk size for Hello", encoded.contains("5\r\nHello\r\n"));
        // Should contain chunk size "5" for "World"
        assertTrue("should contain chunk size for World", encoded.contains("5\r\nWorld\r\n"));
        // Should end with last chunk marker
        assertTrue("should contain last chunk marker", encoded.contains("0\r\n"));

        neta.shutdown();
    }

    // ========================= Encode HEAD Request =========================

    @Test
    public void testEncodeHeadRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/status");
        request.headers().add("Host", "www.example.com");
        client.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HEAD /status HTTP/1.1\r\n"));

        neta.shutdown();
    }

    // ========================= Encode HTTP/1.0 Request =========================

    @Test
    public void testEncodeHttp10Request() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
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

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("GET / HTTP/1.0\r\n"));

        neta.shutdown();
    }

    // ========================= Encode Multiple Headers =========================

    @Test
    public void testEncodeMultipleHeaders() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpRequestEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().add("Host", "example.com");
        request.headers().add("Accept", "text/html");
        request.headers().add("Accept", "application/json");
        request.headers().add("User-Agent", "TestClient/1.0");
        client.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("host: example.com\r\n"));
        assertTrue(encoded.contains("user-agent: TestClient/1.0\r\n"));

        neta.shutdown();
    }
}
