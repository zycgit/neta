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
import net.hasor.neta.codec.http.constant.HttpVersion;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpRequestDecoder}.
 */
public class HttpRequestDecoderTest {

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
        new HttpRequestDecoder(-1, 8192, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxInitialLineLength() {
        new HttpRequestDecoder(0, 8192, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxHeaderSize() {
        new HttpRequestDecoder(4096, -1, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxHeaderSize() {
        new HttpRequestDecoder(4096, 0, 8192);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxChunkSize() {
        new HttpRequestDecoder(4096, 8192, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroMaxChunkSize() {
        new HttpRequestDecoder(4096, 8192, 0);
    }

    // ========================= Simple GET Request =========================

    @Test
    public void testSimpleGetRequest() throws Throwable {
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

        String request = "GET /index.html HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        // Expect: HttpRequest + LastHttpContent
        assertEquals(2, rcvData.size());

        Object first = rcvData.poll();
        assertTrue("first should be HttpRequest, got: " + first.getClass().getName(), first instanceof HttpRequest);
        HttpRequest req = (HttpRequest) first;
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/index.html", req.uri());
        assertEquals(HttpVersion.HTTP_1_1, req.protocolVersion());
        assertEquals("www.example.com", req.headers().get("host"));

        Object second = rcvData.poll();
        assertTrue("second should be LastHttpContent", second instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= GET with HTTP/1.0 =========================

    @Test
    public void testGetRequestHttp10() throws Throwable {
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

        String request = "GET / HTTP/1.0\r\n" + "Host: localhost\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, req.method());
        assertEquals("/", req.uri());
        assertEquals(HttpVersion.HTTP_1_0, req.protocolVersion());

        neta.shutdown();
    }

    // ========================= POST with Content-Length =========================

    @Test
    public void testPostWithContentLength() throws Throwable {
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

        String body = "name=value&foo=bar";
        String request = "POST /submit HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Content-Length: " + body.length() + "\r\n" + "Content-Type: application/x-www-form-urlencoded\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        // Expect: HttpRequest + LastHttpContent (with body)
        assertTrue("should have at least 2 messages, got " + rcvData.size(), rcvData.size() >= 2);

        Object first = rcvData.poll();
        assertTrue(first instanceof HttpRequest);
        HttpRequest req = (HttpRequest) first;
        assertEquals(HttpMethod.POST, req.method());
        assertEquals("/submit", req.uri());
        assertEquals("www.example.com", req.headers().get("host"));
        assertEquals(String.valueOf(body.length()), req.headers().get("content-length"));

        // Collect all content (may span multiple HttpContent + final LastHttpContent)
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

    // ========================= POST with Chunked Transfer Encoding =========================

    @Test
    public void testPostWithChunkedEncoding() throws Throwable {
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

        String request = "POST /upload HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\n" + "Hello\r\n" + "6\r\n" + "World!\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue("should have messages", rcvData.size() >= 2);

        Object first = rcvData.poll();
        assertTrue(first instanceof HttpRequest);
        HttpRequest req = (HttpRequest) first;
        assertEquals(HttpMethod.POST, req.method());
        assertEquals("/upload", req.uri());

        // Collect all chunked body content
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
        assertEquals("HelloWorld!", bodyContent.toString());

        neta.shutdown();
    }

    // ========================= Multiple Headers =========================

    @Test
    public void testMultipleHeaders() throws Throwable {
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

        String request = "GET /page HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Accept: text/html\r\n" + "Accept: application/json\r\n" + "User-Agent: TestClient/1.0\r\n" + "Connection: keep-alive\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("www.example.com", req.headers().get("host"));
        assertEquals("TestClient/1.0", req.headers().get("user-agent"));
        assertEquals("keep-alive", req.headers().get("connection"));

        // Multiple Accept headers
        java.util.List<String> accepts = req.headers().getAll("accept");
        assertEquals(2, accepts.size());
        assertTrue(accepts.contains("text/html"));
        assertTrue(accepts.contains("application/json"));

        neta.shutdown();
    }

    // ========================= HEAD Request (no body) =========================

    @Test
    public void testHeadRequest() throws Throwable {
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

        String request = "HEAD /status HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals(HttpMethod.HEAD, req.method());
        assertEquals("/status", req.uri());

        Object last = rcvData.poll();
        assertTrue(last instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= Chunked with Trailing Headers =========================

    @Test
    public void testChunkedWithTrailingHeaders() throws Throwable {
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

        String request = "POST /data HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "Trailer: Checksum\r\n" + "\r\n" + "4\r\n" + "Test\r\n" + "0\r\n" + "Checksum: abc123\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue(rcvData.size() >= 2);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpRequest);

        // Find LastHttpContent with trailing headers
        Object msg;
        LastHttpContent lastContent = null;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof LastHttpContent) {
                lastContent = (LastHttpContent) msg;
            }
        }
        assertNotNull("should have received LastHttpContent", lastContent);
        assertNotNull(lastContent.trailerHeaders());
        assertEquals("abc123", lastContent.trailerHeaders().get("checksum"));

        neta.shutdown();
    }

    // ========================= Various HTTP methods =========================

    @Test
    public void testHttpMethods() throws Throwable {
        String[] methods = { "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS" };
        for (String methodStr : methods) {
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

            String request = methodStr + " /api HTTP/1.1\r\n" + "Host: localhost\r\n" + "\r\n";
            client.sendData(toByteBuf(request)).get();

            assertTrue("should have messages for " + methodStr, rcvData.size() >= 1);
            Object first = rcvData.poll();
            assertTrue(first instanceof HttpRequest);
            assertEquals(HttpMethod.valueOf(methodStr), ((HttpRequest) first).method());

            neta.shutdown();
        }
    }

    // ========================= Different URI formats =========================

    @Test
    public void testDifferentUriFormats() throws Throwable {
        String[] uris = { "/", "/index.html", "/path/to/resource", "/search?q=test&page=1", "/path#fragment", "http://example.com/absolute" };
        for (String uri : uris) {
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

            String request = "GET " + uri + " HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
            client.sendData(toByteBuf(request)).get();

            assertTrue("should have messages for URI: " + uri, rcvData.size() >= 1);
            HttpRequest req = (HttpRequest) rcvData.poll();
            assertEquals(uri, req.uri());

            neta.shutdown();
        }
    }

    // ========================= POST with zero Content-Length =========================

    @Test
    public void testPostWithZeroContentLength() throws Throwable {
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

        String request = "POST /empty HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Content-Length: 0\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(2, rcvData.size());
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpRequest);
        HttpRequest req = (HttpRequest) first;
        assertEquals(HttpMethod.POST, req.method());

        Object last = rcvData.poll();
        assertTrue(last instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= Chunk extension parsing =========================

    @Test
    public void testChunkExtension() throws Throwable {
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

        // Chunk size with extension per RFC 7230 §4.1
        String request = "POST /data HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5;ext=val\r\n" + "Hello\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue(rcvData.size() >= 2);
        Object first = rcvData.poll();
        assertTrue(first instanceof HttpRequest);

        StringBuilder bodyContent = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            if (msg instanceof HttpContent) {
                ByteBuf content = ((HttpContent) msg).content();
                if (content.readableBytes() > 0) {
                    bodyContent.append(content.readString(content.readableBytes(), StandardCharsets.US_ASCII));
                }
            }
        }
        assertEquals("Hello", bodyContent.toString());

        neta.shutdown();
    }

    // ========================= Split data arrival (partial request) =========================

    @Test
    public void testSplitDataArrival() throws Throwable {
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

        // Send request in multiple fragments
        client.sendData(toByteBuf("GET /split")).get();
        client.sendData(toByteBuf(" HTTP/1.1\r\n")).get();
        client.sendData(toByteBuf("Host: example")).get();
        client.sendData(toByteBuf(".com\r\n")).get();
        client.sendData(toByteBuf("\r\n")).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/split", decoded.uri());
        assertEquals("example.com", decoded.headers().get("host"));

        neta.shutdown();
    }

    // ========================= Large body with Content-Length =========================

    @Test
    public void testLargeBody() throws Throwable {
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

        // Create a body of 2000 bytes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String body = sb.toString();
        String request = "POST /large HTTP/1.1\r\n" + "Host: example.com\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        ByteBuf content = decoded.content();
        String decodedBody = content.readString(content.readableBytes(), StandardCharsets.US_ASCII);
        assertEquals(body, decodedBody);

        neta.shutdown();
    }

    // ========================= Empty URI request =========================

    @Test
    public void testRequestWithQueryString() throws Throwable {
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

        String request = "GET /search?q=hello+world&lang=en&page=1 HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue(rcvData.size() >= 1);
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("/search?q=hello+world&lang=en&page=1", req.uri());

        neta.shutdown();
    }

    // ========================= Request with empty header value =========================

    @Test
    public void testHeaderWithEmptyValue() throws Throwable {
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

        String request = "GET / HTTP/1.1\r\n" + "Host: example.com\r\n" + "X-Empty:\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue(rcvData.size() >= 1);
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals("", req.headers().get("x-empty"));

        neta.shutdown();
    }

    // ========================= DELETE request (no body by spec) =========================

    @Test
    public void testDeleteRequest() throws Throwable {
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

        String request = "DELETE /resource/42 HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(2, rcvData.size());
        HttpRequest req = (HttpRequest) rcvData.poll();
        assertEquals(HttpMethod.DELETE, req.method());
        assertEquals("/resource/42", req.uri());
        assertTrue(rcvData.poll() instanceof LastHttpContent);

        neta.shutdown();
    }

    // ========================= PUT request with body =========================

    @Test
    public void testPutRequestWithBody() throws Throwable {
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

        String body = "{\"name\":\"updated\"}";
        String request = "PUT /resource/1 HTTP/1.1\r\n" + "Host: api.example.com\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.PUT, decoded.method());
        assertEquals("/resource/1", decoded.uri());
        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Multiple chunks in chunked encoding =========================

    @Test
    public void testMultipleChunks() throws Throwable {
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

        String request = "POST /chunks HTTP/1.1\r\n" + "Host: example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "1\r\nA\r\n" + "2\r\nBC\r\n" + "3\r\nDEF\r\n" + "4\r\nGHIJ\r\n" + "0\r\n\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        ByteBuf content = decoded.content();
        assertEquals("ABCDEFGHIJ", content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Error-capturing decoder helper =========================

    @Test
    public void testRequestLineTooLong() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(20, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Request line is 48 chars, exceeds maxInitialLineLength=20
        String request = "GET /this-is-a-very-long-uri-path HTTP/1.1\r\n" + "Host: x\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught request line too long error", decoder.lastError);
        assertTrue("Should be HttpInitialLineTooLongException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpInitialLineTooLongException);
        assertTrue(decoder.lastError.getMessage().contains("request line too long"));
        // No valid request should be produced
        for (Object msg : rcvData) {
            assertFalse("Should NOT receive HttpRequest", msg instanceof HttpRequest);
        }

        neta.shutdown();
    }

    // ========================= Request line too long =========================

    @Test
    public void testHeadersTooLarge() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 50, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Each header line: ~20 bytes + 2 CRLF = ~22 bytes. Three headers > 50 bytes
        String request = "GET / HTTP/1.1\r\n" + "Host: www.example.com\r\n" + "Accept: text/html\r\n" + "User-Agent: TestClient\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught headers too large error", decoder.lastError);
        assertTrue("Should be HttpHeaderTooLargeException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpHeaderTooLargeException);
        assertTrue(decoder.lastError.getMessage().contains("HTTP headers too large"));

        neta.shutdown();
    }

    // ========================= Headers too large =========================

    @Test
    public void testInvalidRequestLineNoVersion() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Missing HTTP version
        String request = "GET /path\r\n" + "Host: x\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught invalid request line error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid request line"));

        neta.shutdown();
    }

    // ========================= Invalid request line: missing version =========================

    @Test
    public void testInvalidRequestLineNoSpaces() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Completely malformed request line
        String request = "GARBAGE\r\n" + "Host: x\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught invalid request line error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid request line"));

        neta.shutdown();
    }

    // ========================= Invalid request line: no spaces at all =========================

    @Test
    public void testInvalidChunkSizeNonHex() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // "XYZ" is not valid hex
        String request = "POST /data HTTP/1.1\r\n" + "Host: x\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "XYZ\r\n" + "Hello\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught invalid chunk size error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid chunk size"));

        neta.shutdown();
    }

    // ========================= Invalid chunk size (non-hex) =========================

    @Test
    public void testNegativeContentLength() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        String request = "POST /data HTTP/1.1\r\n" + "Host: x\r\n" + "Content-Length: -5\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught negative Content-Length error", decoder.lastError);
        assertTrue("Should be HttpContentTooLargeException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpContentTooLargeException);
        assertTrue(decoder.lastError.getMessage().contains("negative Content-Length"));

        neta.shutdown();
    }

    // ========================= Negative Content-Length =========================

    @Test
    public void testPipeliningMultipleRequests() throws Throwable {
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

        // Three requests in a single buffer (HTTP pipelining)
        String req1 = "GET /first HTTP/1.1\r\n" + "Host: example.com\r\n" + "Connection: keep-alive\r\n" + "\r\n";
        String req2 = "POST /second HTTP/1.1\r\n" + "Host: example.com\r\n" + "Connection: keep-alive\r\n" + "Content-Length: 5\r\n" + "\r\n" + "hello";
        String req3 = "GET /third HTTP/1.1\r\n" + "Host: example.com\r\n" + "Connection: close\r\n" + "\r\n";
        // Send all three pipelined in a single buffer
        client.sendData(toByteBuf(req1 + req2 + req3)).get();

        assertEquals("Should decode all 3 pipelined requests", 3, rcvData.size());

        FullHttpRequest decoded1 = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded1.method());
        assertEquals("/first", decoded1.uri());
        assertEquals(0, decoded1.content().readableBytes());

        FullHttpRequest decoded2 = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded2.method());
        assertEquals("/second", decoded2.uri());
        ByteBuf body2 = decoded2.content();
        assertEquals("hello", body2.readString(body2.readableBytes(), StandardCharsets.US_ASCII));

        FullHttpRequest decoded3 = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded3.method());
        assertEquals("/third", decoded3.uri());

        neta.shutdown();
    }

    // ========================= Pipelining: multiple keep-alive requests in single buffer =========================

    @Test
    public void testPipeliningMixedRequests() throws Throwable {
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

        // GET followed by chunked POST, all in one buffer
        String req1 = "GET /api/status HTTP/1.1\r\n" + "Host: example.com\r\n" + "\r\n";
        String req2 = "POST /api/upload HTTP/1.1\r\n" + "Host: example.com\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "5\r\nHello\r\n" + "6\r\n World\r\n" + "0\r\n" + "\r\n";
        client.sendData(toByteBuf(req1 + req2)).get();

        assertEquals("Should decode both pipelined requests", 2, rcvData.size());

        FullHttpRequest decoded1 = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded1.method());
        assertEquals("/api/status", decoded1.uri());

        FullHttpRequest decoded2 = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded2.method());
        assertEquals("/api/upload", decoded2.uri());
        ByteBuf body = decoded2.content();
        assertEquals("Hello World", body.readString(body.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    // ========================= Pipelining: mixed GET and chunked POST =========================

    @Test
    public void testObsFoldHeaderContinuation() throws Throwable {
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

        // Header with obs-fold continuation (leading whitespace on continuation line)
        String request = "GET /fold HTTP/1.1\r\n" + "Host: example.com\r\n" + "X-Long-Header: first-part\r\n" + " second-part\r\n" + "\tsecond-tab-part\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertTrue(rcvData.size() >= 1);
        HttpRequest req = (HttpRequest) rcvData.poll();
        String headerValue = req.headers().get("x-long-header");
        // obs-fold should be replaced with SP and appended
        assertTrue("obs-fold continuation should be appended", headerValue.contains("first-part"));
        assertTrue("obs-fold continuation should contain second-part", headerValue.contains("second-part"));
        assertTrue("obs-fold continuation should contain second-tab-part", headerValue.contains("second-tab-part"));

        neta.shutdown();
    }

    // ========================= Obs-fold header continuation (RFC 7230 §3.2.4) =========================

    @Test
    public void testInvalidHeaderLineNoColon() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Header line without colon
        String request = "GET / HTTP/1.1\r\n" + "InvalidHeader\r\n" + "\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught invalid header line error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("invalid header line"));

        neta.shutdown();
    }

    // ========================= Invalid header line (no colon) =========================

    @Test
    public void testEmptyChunkSize() throws Throwable {
        NetManager neta = new NetManager();
        ErrorCapturingRequestDecoder decoder = new ErrorCapturingRequestDecoder(4096, 8192, 8192);
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLastDecoder(decoder);
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        // Empty chunk size line
        String request = "POST /data HTTP/1.1\r\n" + "Host: x\r\n" + "Transfer-Encoding: chunked\r\n" + "\r\n" + "\r\n" + "Hello\r\n";
        client.sendData(toByteBuf(request)).get();

        assertNotNull("Should have caught empty chunk size error", decoder.lastError);
        assertTrue("Should be HttpMalformedRequestException", decoder.lastError instanceof net.hasor.neta.codec.http.exception.HttpMalformedRequestException);
        assertTrue(decoder.lastError.getMessage().contains("empty chunk size"));

        neta.shutdown();
    }

    // ========================= Empty chunk size =========================

    private static class ErrorCapturingRequestDecoder extends HttpRequestDecoder {
        volatile Throwable lastError;

        ErrorCapturingRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
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
