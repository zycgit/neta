/*
 * CORS Example Test - validates the code examples from http_cors.md
 */
package net.hasor.neta.example.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.http.cors.CorsHandler;
import net.hasor.neta.codec.http.cors.CorsUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests CORS examples from http_cors.md documentation.
 * Uses Neta HTTP server with CorsHandler and Java HttpURLConnection as client.
 */
public class HttpCorsExampleTest {

    private NetManager neta;
    private int        port;
    private CorsConfig corsConfig;

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

    /** Read raw HTTP response from socket (headers + body) */
    private static String readRawResponse(Socket socket) throws IOException {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        // Read headers byte-by-byte until \r\n\r\n
        int state = 0; // 0=normal, 1=\r, 2=\r\n, 3=\r\n\r
        boolean headersDone = false;
        int b;
        try {
            while ((b = in.read()) != -1) {
                bos.write(b);
                switch (state) {
                    case 0:
                        state = (b == '\r') ? 1 : 0;
                        break;
                    case 1:
                        state = (b == '\n') ? 2 : (b == '\r') ? 1 : 0;
                        break;
                    case 2:
                        state = (b == '\r') ? 3 : 0;
                        break;
                    case 3:
                        if (b == '\n') {
                            headersDone = true;
                        } else {
                            state = (b == '\r') ? 1 : 0;
                        }
                        break;
                }
                if (headersDone)
                    break;
            }
        } catch (SocketTimeoutException e) {
            return bos.toString("UTF-8");
        }

        if (!headersDone) {
            return bos.toString("UTF-8");
        }

        // Parse Content-Length from headers to read exact body
        String headerSection = bos.toString("UTF-8").toLowerCase();
        int clIdx = headerSection.indexOf("content-length:");
        if (clIdx >= 0) {
            String after = headerSection.substring(clIdx + "content-length:".length());
            int eol = after.indexOf("\r\n");
            if (eol < 0)
                eol = after.indexOf("\n");
            if (eol < 0)
                eol = after.length();
            int contentLength = Integer.parseInt(after.substring(0, eol).trim());

            // Read exactly contentLength bytes of body
            byte[] body = new byte[contentLength];
            int read = 0;
            try {
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n == -1)
                        break;
                    read += n;
                }
            } catch (SocketTimeoutException e) {
                // partial body
            }
            bos.write(body, 0, read);
        }

        return bos.toString("UTF-8");
    }

    /** Check if HTTP response contains a header (case-insensitive) */
    private static boolean containsHeader(String response, String headerName) {
        String lower = response.toLowerCase();
        String target = headerName.toLowerCase() + ":";
        return lower.contains(target);
    }

    /** Check if HTTP response contains a header with specific value */
    private static boolean containsHeader(String response, String headerName, String expectedValue) {
        String[] lines = response.split("\r?\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase(headerName) && value.equals(expectedValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Before
    public void setUp() throws Exception {
        port = findFreePort();
        neta = new NetManager();

        // CORS config from doc §快速开始
        corsConfig = CorsConfig.builder().allowOrigins("https://example.com", "https://app.example.com").allowMethods("GET", "POST", "PUT", "DELETE").allowHeaders("Content-Type", "Authorization", "X-Requested-With").exposeHeaders("X-Custom-Header").allowCredentials(true).maxAge(3600).build();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    private void startCorsServer(CorsConfig config) throws Exception {
        // Pipeline from doc §配置 Pipeline
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(65536));
            ctx.addLastDecoder("cors", new CorsHandler(config));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());

        // Request handling from doc §处理请求和响应
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object msg = payload.getData();
            NetChannel channel = (NetChannel) payload.getSource();

            if (msg instanceof FullHttpResponse) {
                // CorsHandler auto-generated preflight response - send back
                channel.sendData(msg);
                return;
            }

            // Normal request - build business response
            FullHttpRequest request = (FullHttpRequest) msg;

            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(256);
            body.writeString("{\"message\":\"Hello CORS!\"}", StandardCharsets.UTF_8);
            body.markWriter();

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "application/json");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            response.headers().set("X-Custom-Header", "custom-value");

            // Apply CORS headers for normal cross-origin requests (from doc)
            CorsUtil.applySimpleCorsHeaders(request, response, config);

            channel.sendData(response);
        });

        Thread.sleep(200);
    }

    /**
     * Test: Preflight OPTIONS request (from http_cors.md §预检请求)
     * CorsHandler should auto-generate 204 response.
     */
    @Test
    public void testPreflightRequest() throws Exception {
        startCorsServer(corsConfig);

        // Use raw Socket since HttpURLConnection restricts Origin header
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String request = "OPTIONS /api/data HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n" + "Connection: close\r\n" + "Origin: https://example.com\r\n" + "Access-Control-Request-Method: POST\r\n" + "Access-Control-Request-Headers: Content-Type, Authorization\r\n" + "\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readRawResponse(socket);
            assertTrue("Should be 204: " + response, response.startsWith("HTTP/1.1 204"));
            assertTrue("Should have Allow-Origin in: " + response, containsHeader(response, "Access-Control-Allow-Origin", "https://example.com"));
            assertTrue("Should have Allow-Methods in: " + response, containsHeader(response, "Access-Control-Allow-Methods"));
            assertTrue("Should have Allow-Headers in: " + response, containsHeader(response, "Access-Control-Allow-Headers"));
            assertTrue("Should have Allow-Credentials in: " + response, containsHeader(response, "Access-Control-Allow-Credentials", "true"));
            assertTrue("Should have Max-Age in: " + response, containsHeader(response, "Access-Control-Max-Age", "3600"));
        }
    }

    /**
     * Test: Simple cross-origin request (from http_cors.md §简单跨域请求)
     * Normal request with Origin should pass through with CORS response headers.
     */
    @Test
    public void testSimpleCorsRequest() throws Exception {
        startCorsServer(corsConfig);

        // Use raw Socket since HttpURLConnection restricts Origin header
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String request = "GET /api/data HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n" + "Connection: close\r\n" + "Origin: https://example.com\r\n" + "\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readRawResponse(socket);
            assertTrue("Should be 200: " + response, response.startsWith("HTTP/1.1 200"));
            assertTrue("Should have Allow-Origin in: " + response, containsHeader(response, "Access-Control-Allow-Origin", "https://example.com"));
            assertTrue("Should have Allow-Credentials in: " + response, containsHeader(response, "Access-Control-Allow-Credentials", "true"));
            assertTrue("Should have Expose-Headers in: " + response, containsHeader(response, "Access-Control-Expose-Headers", "X-Custom-Header"));
            assertTrue("Should contain body in: " + response, response.contains("{\"message\":\"Hello CORS!\"}"));
        }
    }

    /**
     * Test: Same-origin request (no Origin header) (from http_cors.md §同源请求)
     * CorsHandler should pass through without adding CORS headers.
     */
    @Test
    public void testNoOriginNoCorsHeaders() throws Exception {
        startCorsServer(corsConfig);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/data").openConnection();
        conn.setRequestMethod("GET");
        // No Origin header
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());

        // No CORS headers should be present
        assertNull(conn.getHeaderField("Access-Control-Allow-Origin"));

        String responseBody = readResponse(conn);
        assertEquals("{\"message\":\"Hello CORS!\"}", responseBody);
        conn.disconnect();
    }

    // -- helpers --

    /**
     * Test: CorsConfig builder - allowAnyOrigin (from http_cors.md §常用配置模板)
     */
    @Test
    public void testAllowAnyOrigin() throws Exception {
        CorsConfig devConfig = CorsConfig.builder().allowAnyOrigin().allowMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS").allowHeaders("Content-Type", "Authorization").maxAge(86400).build();

        startCorsServer(devConfig);

        // Use raw Socket since HttpURLConnection restricts Origin header
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String request = "OPTIONS /api/data HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n" + "Connection: close\r\n" + "Origin: https://any-origin.example.com\r\n" + "Access-Control-Request-Method: PUT\r\n" + "\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readRawResponse(socket);
            assertTrue("Should be 204: " + response, response.startsWith("HTTP/1.1 204"));
            assertTrue("Should have * Allow-Origin in: " + response, containsHeader(response, "Access-Control-Allow-Origin", "*"));
            assertTrue("Should have Max-Age 86400 in: " + response, containsHeader(response, "Access-Control-Max-Age", "86400"));
        }
    }

    /**
     * Test: Disabled CORS (from http_cors.md §禁用 CORS)
     */
    @Test
    public void testDisabledCors() throws Exception {
        CorsConfig disabled = CorsConfig.builder().disable().build();

        startCorsServer(disabled);

        // Even a preflight should pass through
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/data").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Origin", "https://example.com");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        // No CORS headers when disabled
        assertNull(conn.getHeaderField("Access-Control-Allow-Origin"));
        conn.disconnect();
    }

    /**
     * Test: CorsConfig query methods (from http_cors.md §CorsConfig 查询方法)
     */
    @Test
    public void testCorsConfigQueryMethods() {
        CorsConfig config = CorsConfig.builder().allowOrigins("https://example.com").build();

        // from doc §CorsConfig 查询方法
        assertTrue(config.isEnabled());
        assertFalse(config.isAnyOrigin());
        assertTrue(config.allowedOrigins().contains("https://example.com"));
        assertTrue(config.isOriginAllowed("https://example.com"));
        assertFalse(config.isOriginAllowed("https://evil.com"));

        // default methods
        assertTrue(config.allowedMethods().contains("GET"));
        assertTrue(config.allowedMethods().contains("HEAD"));
        assertTrue(config.allowedMethods().contains("POST"));

        assertTrue(config.allowedHeaders().isEmpty());
        assertTrue(config.exposedHeaders().isEmpty());
        assertFalse(config.isAllowCredentials());
        assertEquals(-1, config.maxAge());
    }

    /**
     * Test: CorsUtil request detection (from http_cors.md §请求检测)
     */
    @Test
    public void testCorsUtilRequestDetection() {
        // Preflight request
        DefaultFullHttpRequest preflight = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/api");
        preflight.headers().set("Origin", "https://example.com");
        preflight.headers().set("Access-Control-Request-Method", "POST");
        assertTrue(CorsUtil.isPreflightRequest(preflight));

        // Non-preflight (missing Access-Control-Request-Method)
        DefaultFullHttpRequest normal = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api");
        normal.headers().set("Origin", "https://example.com");
        assertFalse(CorsUtil.isPreflightRequest(normal));

        // Get origin
        assertEquals("https://example.com", CorsUtil.getOrigin(preflight));
    }

    /**
     * Test: allowAnyOrigin + allowCredentials conflict (from doc :::caution)
     */
    @Test(expected = IllegalStateException.class)
    public void testAnyOriginWithCredentialsConflict() {
        CorsConfig.builder().allowAnyOrigin().allowCredentials(true).build();
    }
}
