/*
 * HTTP Basics Example Test - validates the code examples from http_basics.md
 */
package net.hasor.neta.example.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.CookieEncoder;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import net.hasor.neta.codec.http.cookie.ServerCookieEncoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests HTTP basics examples from http_basics.md documentation.
 * Uses Neta HTTP as server and Java HttpURLConnection as client.
 */
public class HttpBasicsExampleTest {

    private NetManager neta;
    private int        port;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null)
            return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toString("UTF-8");
    }

    @Before
    public void setUp() throws Exception {
        Thread.sleep(50); // allow previous port release
        port = findFreePort();
        neta = new NetManager();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    /**
     * Test: Quick Start HTTP Server - GET request handling (from http_basics.md §快速开始：HTTP 服务器)
     */
    @Test
    public void testGetRequest() throws Exception {
        // --- Server setup (from doc) ---
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();

            // read request info
            HttpMethod method = request.method();
            String uri = request.uri();

            // build response (from doc)
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("Hello, Neta HTTP!", StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain; charset=UTF-8");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));

            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        // --- Client ---
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/data").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        assertEquals("text/plain; charset=UTF-8", conn.getHeaderField("Content-Type"));

        String responseBody = readResponse(conn);
        assertEquals("Hello, Neta HTTP!", responseBody);
        conn.disconnect();
    }

    /**
     * Test: POST request with JSON body (from http_basics.md §构建请求/构建响应)
     */
    @Test
    public void testPostRequestWithJsonBody() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();

            // verify request
            String method = request.method().name();
            String contentType = request.headers().get("Content-Type");

            // read request body
            ByteBuf requestContent = request.content();
            String requestBody = requestContent.readString(requestContent.readableBytes(), StandardCharsets.UTF_8);

            // build JSON response (from doc)
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("{\"method\":\"" + method + "\",\"body\":\"" + requestBody + "\"}", StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse ok = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            ok.headers().set("Content-Type", "application/json");
            ok.headers().set("Content-Length", String.valueOf(body.readableBytes()));

            ((NetChannel) payload.getSource()).sendData(ok);
        });

        Thread.sleep(200);

        // --- Client: send POST with JSON ---
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/users").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        String jsonPayload = "{\"name\":\"neta\"}";
        conn.getOutputStream().write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        assertEquals(200, conn.getResponseCode());
        assertEquals("application/json", conn.getHeaderField("Content-Type"));

        String responseBody = readResponse(conn);
        assertTrue(responseBody.contains("\"method\":\"POST\""));
        assertTrue(responseBody.contains("{\"name\":\"neta\"}"));
        conn.disconnect();
    }

    /**
     * Test: 404 Not Found response (from http_basics.md §构建响应)
     */
    @Test
    public void testNotFoundResponse() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            // 404 Not Found (no Body) - from doc
            FullHttpResponse notFound = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NOT_FOUND);
            notFound.headers().set("Content-Length", "0");

            ((NetChannel) payload.getSource()).sendData(notFound);
        });

        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/missing").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(404, conn.getResponseCode());
        conn.disconnect();
    }

    /**
     * Test: HttpHeaders operations (from http_basics.md §HttpHeaders 操作)
     */
    @Test
    public void testHttpHeadersOperations() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();

            // verify headers from doc
            HttpHeaders reqHeaders = request.headers();

            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("headers-ok", StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);

            // HttpHeaders operations from doc
            HttpHeaders headers = response.headers();
            headers.add("Accept", "text/html");
            headers.add("Accept", "application/json");
            headers.set("Content-Type", "text/plain");
            headers.set("Content-Length", String.valueOf(body.readableBytes()));
            headers.set("X-Custom", "test-value");

            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/test").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        assertEquals("text/plain", conn.getHeaderField("Content-Type"));
        assertEquals("test-value", conn.getHeaderField("X-Custom"));
        conn.disconnect();
    }

    /**
     * Test: Multiple requests on same connection (Keep-Alive) (from http_basics.md §Keep-Alive 支持)
     */
    @Test
    public void testKeepAliveMultipleRequests() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            String uri = request.uri();

            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("Response for " + uri, StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            // keep-alive is default in HTTP/1.1

            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        // Send multiple requests (HTTP/1.1 keep-alive)
        for (int i = 1; i <= 3; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/req" + i).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            assertEquals(200, conn.getResponseCode());
            String responseBody = readResponse(conn);
            assertEquals("Response for /req" + i, responseBody);
            conn.disconnect();
        }
    }

    /**
     * Test: HttpStatus codes (from http_basics.md §HTTP 状态码)
     */
    @Test
    public void testHttpStatusCodes() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            String uri = request.uri();

            HttpStatus status;
            if ("/created".equals(uri)) {
                status = HttpStatus.CREATED;
            } else if ("/no-content".equals(uri)) {
                status = HttpStatus.NO_CONTENT;
            } else if ("/redirect".equals(uri)) {
                FullHttpResponse redirect = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.MOVED_PERMANENTLY);
                redirect.headers().set("Location", "https://example.com/new-path");
                redirect.headers().set("Content-Length", "0");
                ((NetChannel) payload.getSource()).sendData(redirect);
                return;
            } else {
                status = HttpStatus.OK;
            }

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.headers().set("Content-Length", "0");
            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        // Test 201 Created
        HttpURLConnection conn1 = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/created").openConnection();
        conn1.setConnectTimeout(5000);
        conn1.setReadTimeout(5000);
        assertEquals(201, conn1.getResponseCode());
        conn1.disconnect();

        // Test 204 No Content
        HttpURLConnection conn2 = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/no-content").openConnection();
        conn2.setConnectTimeout(5000);
        conn2.setReadTimeout(5000);
        assertEquals(204, conn2.getResponseCode());
        conn2.disconnect();

        // Test 301 Redirect
        HttpURLConnection conn3 = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/redirect").openConnection();
        conn3.setInstanceFollowRedirects(false);
        conn3.setConnectTimeout(5000);
        conn3.setReadTimeout(5000);
        assertEquals(301, conn3.getResponseCode());
        assertEquals("https://example.com/new-path", conn3.getHeaderField("Location"));
        conn3.disconnect();
    }

    // -- helpers --

    /**
     * Test: Cookie handling (from http_basics.md §Cookie 处理)
     */
    @Test
    public void testCookieHandling() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();

            // Decode request cookies (from doc)
            String cookieHeader = request.headers().get("Cookie");
            String receivedCookies = (cookieHeader != null) ? cookieHeader : "none";

            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("cookies:" + receivedCookies, StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));

            // Set-Cookie encoding (from doc)
            DefaultCookie sessionCookie = new DefaultCookie("session", "xyz789");
            sessionCookie.setDomain("localhost");
            sessionCookie.setPath("/");
            sessionCookie.setMaxAge(3600);
            sessionCookie.setHttpOnly(true);

            String setCookieValue = ServerCookieEncoder.encode(sessionCookie);
            response.headers().add("Set-Cookie", setCookieValue);

            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/cookie").openConnection();
        conn.setRequestProperty("Cookie", CookieEncoder.encode(new DefaultCookie("session", "abc123"), new DefaultCookie("lang", "zh-CN")));
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());

        // Verify Set-Cookie response header
        String setCookie = conn.getHeaderField("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("session=xyz789"));
        assertTrue(setCookie.contains("HttpOnly"));

        // Verify request cookies were received
        String responseBody = readResponse(conn);
        assertTrue(responseBody.contains("session=abc123"));
        assertTrue(responseBody.contains("lang=zh-CN"));
        conn.disconnect();
    }

    /**
     * Test: Custom HttpServerDuplexe limits (from http_basics.md §自定义解码器限制)
     */
    @Test
    public void testCustomDecoderLimits() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            // from doc: custom limits
            ctx.addLast("http", new HttpServerDuplexe(4096, 8192, 8192));
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator());
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(32);
            body.writeString("OK", StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));

            ((NetChannel) payload.getSource()).sendData(response);
        });

        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("OK", readResponse(conn));
        conn.disconnect();
    }
}
