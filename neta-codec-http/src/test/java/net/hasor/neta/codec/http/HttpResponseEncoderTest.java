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
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpResponseEncoder}.
 */
public class HttpResponseEncoderTest {

    private static String readByteBuf(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII);
    }

    // ========================= Encode Simple 200 OK =========================

    @Test
    public void testEncodeSimple200Response() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "<html><body>Hello</body></html>";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Type", "text/html");
        response.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("should contain status line", encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue("should contain Content-Type", encoded.contains("content-type: text/html\r\n"));
        assertTrue("should contain body", encoded.contains(body));

        neta.shutdown();
    }

    // ========================= Encode 404 Not Found =========================

    @Test
    public void testEncode404Response() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
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

    // ========================= Encode Chunked Response =========================

    @Test
    public void testEncodeChunkedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send response head
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Transfer-Encoding", "chunked");
        head.headers().add("Content-Type", "text/plain");
        client.sendData(head).get();

        // Send chunk
        ByteBuf chunkBuf = ByteBufAllocator.DEFAULT.buffer(16);
        chunkBuf.writeString("Hello", StandardCharsets.US_ASCII);
        chunkBuf.markWriter();
        client.sendData(new DefaultHttpContent(chunkBuf)).get();

        // Send last chunk
        client.sendData(new DefaultLastHttpContent()).get();

        // Collect all output
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("should contain status line", encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue("should contain chunked header", encoded.contains("transfer-encoding: chunked\r\n"));
        assertTrue("should contain chunk data", encoded.contains("5\r\nHello\r\n"));
        assertTrue("should contain last chunk marker", encoded.contains("0\r\n"));

        neta.shutdown();
    }

    // ========================= Encode 204 No Content =========================

    @Test
    public void testEncode204NoContent() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);
        client.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HTTP/1.1 204 No Content\r\n"));

        neta.shutdown();
    }

    // ========================= Encode HTTP/1.0 Response =========================

    @Test
    public void testEncodeHttp10Response() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "OK";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Length", String.valueOf(body.length()));
        client.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("HTTP/1.0 200 OK\r\n"));
        assertTrue(encoded.contains("OK"));

        neta.shutdown();
    }

    // ========================= Encode Chunked with Trailing Headers =========================

    @Test
    public void testEncodeChunkedWithTrailingHeaders() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send response head
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Transfer-Encoding", "chunked");
        head.headers().add("Trailer", "Checksum");
        client.sendData(head).get();

        // Send last content with trailing headers
        HttpHeaders trailers = new HttpHeaders();
        trailers.add("Checksum", "abc123");
        client.sendData(new DefaultLastHttpContent(ByteBuf.EMPTY, trailers)).get();

        // Collect all output
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue(encoded.contains("0\r\n"));
        assertTrue(encoded.contains("checksum: abc123\r\n"));

        neta.shutdown();
    }

    // ========================= Multi-chunk streamed encoding =========================

    @Test
    public void testEncodeMultiChunkStreamedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Send response head
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Transfer-Encoding", "chunked");
        client.sendData(head).get();

        // Send multiple chunks
        for (int i = 1; i <= 3; i++) {
            String chunkData = "chunk" + i;
            ByteBuf chunkBuf = ByteBufAllocator.DEFAULT.buffer(chunkData.length());
            chunkBuf.writeString(chunkData, StandardCharsets.US_ASCII);
            chunkBuf.markWriter();
            client.sendData(new DefaultHttpContent(chunkBuf)).get();
        }

        // Send last chunk (empty body)
        client.sendData(new DefaultLastHttpContent()).get();

        // Collect all output
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();

        // Verify status line
        assertTrue("should contain status line", encoded.contains("HTTP/1.1 200 OK\r\n"));
        // Verify each chunk has proper size + CRLF + data + CRLF format
        assertTrue("should contain chunk1", encoded.contains("6\r\nchunk1\r\n"));
        assertTrue("should contain chunk2", encoded.contains("6\r\nchunk2\r\n"));
        assertTrue("should contain chunk3", encoded.contains("6\r\nchunk3\r\n"));
        // Verify last chunk marker
        assertTrue("should contain last chunk marker (0\\r\\n\\r\\n)", encoded.contains("0\r\n\r\n"));

        neta.shutdown();
    }

    // ========================= Chunked encoder round-trip with decoder =========================

    @Test
    public void testChunkedEncoderDecoderRoundTrip() throws Throwable {
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

        // Streamed chunked encode → decode → aggregate
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Transfer-Encoding", "chunked");
        head.headers().add("Content-Type", "text/plain");
        client.sendData(head).get();

        ByteBuf c1 = ByteBufAllocator.DEFAULT.buffer(16);
        c1.writeString("Hello", StandardCharsets.US_ASCII);
        c1.markWriter();
        client.sendData(new DefaultHttpContent(c1)).get();

        ByteBuf c2 = ByteBufAllocator.DEFAULT.buffer(16);
        c2.writeString(" World", StandardCharsets.US_ASCII);
        c2.markWriter();
        client.sendData(new DefaultLastHttpContent(c2)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(200, decoded.status().code());
        ByteBuf content = decoded.content();
        assertEquals("Hello World", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
        // After aggregation, Transfer-Encoding should be removed and Content-Length set
        assertTrue(decoded.headers().get("transfer-encoding") == null);
        assertEquals("11", decoded.headers().get("content-length"));

        neta.shutdown();
    }

    // ========================= Streamed non-chunked encoding =========================

    @Test
    public void testEncodeStreamedNonChunked() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLastEncoder(new HttpResponseEncoder());
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Non-chunked streaming: Content-Length based
        DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
        head.headers().add("Content-Length", "11");
        client.sendData(head).get();

        ByteBuf b1 = ByteBufAllocator.DEFAULT.buffer(8);
        b1.writeString("Hello", StandardCharsets.US_ASCII);
        b1.markWriter();
        client.sendData(new DefaultHttpContent(b1)).get();

        ByteBuf b2 = ByteBufAllocator.DEFAULT.buffer(8);
        b2.writeString(" World", StandardCharsets.US_ASCII);
        b2.markWriter();
        client.sendData(new DefaultLastHttpContent(b2)).get();

        // Collect all output
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();

        // Non-chunked: should NOT have chunk size markers
        assertTrue(encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue(encoded.contains("Hello World"));
        assertFalse("non-chunked should NOT contain chunk size markers", encoded.contains("5\r\nHello\r\n"));

        neta.shutdown();
    }
}
