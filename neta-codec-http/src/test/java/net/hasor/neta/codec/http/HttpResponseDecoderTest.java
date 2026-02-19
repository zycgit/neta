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
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpResponseDecoder}.
 */
public class HttpResponseDecoderTest {

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    // ========================= Constructor Validation =========================

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxInitialLineLength() {
        new HttpResponseDecoder(-1, 8192, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxHeaderSize() {
        new HttpResponseDecoder(4096, 0, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxChunkSize() {
        new HttpResponseDecoder(4096, 8192, -1);
    }

    // ========================= Simple 200 OK Response =========================

    @Test
    public void testSimple200OkResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "<html><body>Hello</body></html>";
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertTrue("should have at least 2 messages", rcvData.size() >= 2);

        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        HttpResponse resp = (HttpResponse) first;
        assertEquals(HttpVersion.HTTP_1_1, resp.protocolVersion());
        assertEquals(HttpStatus.OK, resp.status());
        assertEquals("text/html", resp.headers().get("content-type"));

        // Collect body
        StringBuilder bodyContent = new StringBuilder();
        Object msg;
        boolean gotLast = false;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof HttpContent) {
                ByteBuf content = ((HttpContent) msg).content();
                if (content.readableBytes() > 0) {
                    bodyContent.append(content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
                }
                if (msg instanceof LastHttpContent) {
                    gotLast = true;
                }
            }
        }
        assertTrue("should have received LastHttpContent", gotLast);
        assertEquals(body, bodyContent.toString());

        neta.shutdown();
    }

    // ========================= 204 No Content (no body) =========================

    @Test
    public void testNoContentResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 204 No Content\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(2, rcvData.size());
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        assertEquals(204, ((HttpResponse) first).status().code());

        Object second = rcvData.poll();
        assertTrue(second instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= 304 Not Modified (no body) =========================

    @Test
    public void testNotModifiedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 304 Not Modified\r\n" + "Date: Thu, 01 Jan 2024 00:00:00 GMT\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(2, rcvData.size());
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        assertEquals(304, ((HttpResponse) first).status().code());

        Object second = rcvData.poll();
        assertTrue(second instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= 100 Continue (informational, no body) =========================

    @Test
    public void testContinueResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 100 Continue\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(2, rcvData.size());
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        assertEquals(100, ((HttpResponse) first).status().code());

        Object second = rcvData.poll();
        assertTrue(second instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= Chunked Transfer Encoding =========================

    @Test
    public void testChunkedResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "7\r\n" + "Mozilla\r\n" + "9\r\n" + "Developer\r\n" + "7\r\n" + "Network\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 2);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);

        StringBuilder bodyContent = new StringBuilder();
        Object msg;
        boolean gotLast = false;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof HttpContent) {
                ByteBuf content = ((HttpContent) msg).content();
                if (content.readableBytes() > 0) {
                    bodyContent.append(content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
                }
                if (msg instanceof LastHttpContent) {
                    gotLast = true;
                }
            }
        }
        assertTrue("should have received LastHttpContent", gotLast);
        assertEquals("MozillaDeveloperNetwork", bodyContent.toString());

        neta.shutdown();
    }

    // ========================= HTTP/1.0 Response with Content-Length =========================

    @Test
    public void testHttp10ResponseWithContentLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Hello World";
        String response = "HTTP/1.0 200 OK\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 2);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        HttpResponse resp = (HttpResponse) first;
        assertEquals(HttpVersion.HTTP_1_0, resp.protocolVersion());
        assertEquals(200, resp.status().code());

        // Collect body
        StringBuilder bodyContent = new StringBuilder();
        Object msg;
        boolean gotLast = false;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof HttpContent) {
                ByteBuf content = ((HttpContent) msg).content();
                if (content.readableBytes() > 0) {
                    bodyContent.append(content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
                }
                if (msg instanceof LastHttpContent) {
                    gotLast = true;
                }
            }
        }
        assertTrue(gotLast);
        assertEquals(body, bodyContent.toString());

        neta.shutdown();
    }

    // ========================= Response with no reason phrase =========================

    @Test
    public void testResponseNoReasonPhrase() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Status line with no reason phrase (allowed in RFC 7230)
        String response = "HTTP/1.1 200\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 1);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        assertEquals(200, ((HttpResponse) first).status().code());

        neta.shutdown();
    }

    // ========================= Response with custom status code =========================

    @Test
    public void testCustomStatusCode() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 418 I'm a teapot\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 1);
        HttpResponse resp = (HttpResponse) rcvData.poll();
        assertEquals(418, resp.status().code());
        assertEquals("I'm a teapot", resp.status().reasonPhrase());

        neta.shutdown();
    }

    // ========================= Response with zero Content-Length =========================

    @Test
    public void testZeroContentLength() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(2, rcvData.size());
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);
        assertEquals(200, ((HttpResponse) first).status().code());

        Object second = rcvData.poll();
        assertTrue(second instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= Chunked with trailing headers =========================

    @Test
    public void testChunkedWithTrailingHeaders() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Transfer-Encoding: chunked\r\n" + "Trailer: Checksum\r\n" + "\r\n" + "3\r\n" + "abc\r\n" + "0\r\n" + "Checksum: xyz789\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 2);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpResponse);

        // Find LastHttpContent with trailing headers
        Object msg;
        LastHttpContent lastContent = null;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof LastHttpContent) {
                lastContent = (LastHttpContent) msg;
            }
        }
        assertNotNull(lastContent);
        assertEquals("xyz789", lastContent.trailerHeaders().get("checksum"));

        neta.shutdown();
    }

    // ========================= 301 Redirect Response =========================

    @Test
    public void testRedirectResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 301 Moved Permanently\r\n" + "Location: http://www.example.com/new-page\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 1);
        HttpResponse resp = (HttpResponse) rcvData.poll();
        assertEquals(301, resp.status().code());
        assertEquals("http://www.example.com/new-page", resp.headers().get("location"));

        neta.shutdown();
    }

    // ========================= 500 Internal Server Error =========================

    @Test
    public void testInternalServerErrorResponse() throws Throwable {
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

        String body = "Internal Server Error";
        String response = "HTTP/1.1 500 Internal Server Error\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(500, decoded.status().code());

        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Split data arrival =========================

    @Test
    public void testSplitDataArrival() throws Throwable {
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

        // Send response in multiple fragments
        client.sendData(toByteBuf("HTTP/1.1 ")).get();
        client.sendData(toByteBuf("200 OK\r\n")).get();
        client.sendData(toByteBuf("Content-Length: 5\r\n")).get();
        client.sendData(toByteBuf("\r\n")).get();
        client.sendData(toByteBuf("Hello")).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(200, decoded.status().code());
        ByteBuf content = decoded.content();
        assertEquals("Hello", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Multiple headers with same name =========================

    @Test
    public void testMultipleHeadersWithSameName() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String response = "HTTP/1.1 200 OK\r\n" + "Set-Cookie: a=1\r\n" + "Set-Cookie: b=2\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 1);
        HttpResponse resp = (HttpResponse) rcvData.poll();
        java.util.List<String> cookies = resp.headers().getAll("set-cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.contains("a=1"));
        assertTrue(cookies.contains("b=2"));

        neta.shutdown();
    }

    // ========================= Large response body =========================

    @Test
    public void testLargeResponseBody() throws Throwable {
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

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String body = sb.toString();
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Multiple Sequential Responses =========================

    @Test
    public void testMultipleSequentialResponses() throws Throwable {
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

        String resp1 = "HTTP/1.1 200 OK\r\n" + "Content-Length: 2\r\n" + "\r\n" + "OK";
        String resp2 = "HTTP/1.1 404 Not Found\r\n" + "Content-Length: 9\r\n" + "\r\n" + "Not Found";
        client.sendData(toByteBuf(resp1)).get();
        client.sendData(toByteBuf(resp2)).get();

        assertEquals(2, rcvData.size());
        FullHttpResponse decoded1 = (FullHttpResponse) rcvData.poll();
        assertEquals(200, decoded1.status().code());
        FullHttpResponse decoded2 = (FullHttpResponse) rcvData.poll();
        assertEquals(404, decoded2.status().code());

        neta.shutdown();
    }

    // ========================= 302 Found with Location =========================

    @Test
    public void testFoundRedirectResponse() throws Throwable {
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

        String response = "HTTP/1.1 302 Found\r\n" + "Location: /new-page\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(302, decoded.status().code());
        assertEquals("/new-page", decoded.headers().get("location"));

        neta.shutdown();
    }

    // ========================= Error-capturing decoder helper =========================

    @Test
    public void testStatusLineTooLong() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(20, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Status line is longer than 20 chars
        String response = "HTTP/1.1 200 OK with a very long reason phrase\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertNotNull("Should have caught status line too long error", decoder.lastError);
        assertTrue("Should be HttpInitialLineTooLongException", decoder.lastError instanceof HttpInitialLineTooLongException);
        assertTrue(decoder.lastError.getMessage().contains("status line too long"));

        neta.shutdown();
    }

    // ========================= Status line too long =========================

    @Test
    public void testHeadersTooLarge() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(4096, 30, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Multiple headers that exceed 30 bytes total
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html\r\n" + "Server: TestServer\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertNotNull("Should have caught headers too large error", decoder.lastError);
        assertTrue("Should be HttpHeaderTooLargeException", decoder.lastError instanceof HttpHeaderTooLargeException);
        assertTrue(decoder.lastError.getMessage().contains("HTTP headers too large"));

        neta.shutdown();
    }

    // ========================= Headers too large =========================

    @Test
    public void testNegativeContentLength() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String response = "HTTP/1.1 200 OK\r\n" + "Content-Length: -10\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertNotNull("Should have caught negative Content-Length error", decoder.lastError);
        assertTrue("Should be HttpContentTooLargeException", decoder.lastError instanceof HttpContentTooLargeException);
        assertTrue(decoder.lastError.getMessage().contains("negative Content-Length"));

        neta.shutdown();
    }

    // ========================= Negative Content-Length =========================

    @Test
    public void testInvalidStatusCode() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String response = "HTTP/1.1 ABC Bad Status\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertNotNull("Should have caught invalid status code error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid status code"));

        neta.shutdown();
    }

    // ========================= Invalid status code (non-numeric) =========================

    @Test
    public void testInvalidChunkSizeNonHex() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingResponseDecoder decoder = new ErrorCapturingResponseDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String response = "HTTP/1.1 200 OK\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "ZZZZ\r\n" + "Data\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertNotNull("Should have caught invalid chunk size error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid chunk size"));

        neta.shutdown();
    }

    // ========================= Invalid chunk size in response =========================

    @Test
    public void testObsFoldHeaderContinuation() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(new HttpResponseDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Header with obs-fold continuation
        String response = "HTTP/1.1 200 OK\r\n" + "X-Multi: first-part\r\n" + " second-part\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(response)).get();

        assertTrue(rcvData.size() >= 1);
        HttpResponse resp = (HttpResponse) rcvData.poll();
        String val = resp.headers().get("x-multi");
        assertTrue("obs-fold should append continuation", val.contains("first-part"));
        assertTrue("obs-fold should append continuation", val.contains("second-part"));

        neta.shutdown();
    }

    // ========================= Obs-fold header continuation =========================

    private static class ErrorCapturingResponseDecoder extends HttpResponseDecoder {
        volatile Throwable lastError;

        ErrorCapturingResponseDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
            lastError = e;
            eh.clear();
            return ProtoStatus.Stop;
        }
    }
}
