/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real TCP socket integration tests for HTTP/1.x.
 * <p>
 * Uses Neta server with real TCP binding and Java {@link HttpURLConnection} as client.
 * Validates end-to-end HTTP/1.x encoding/decoding over actual network I/O.
 */
public class HttpRealSocketTest {

    /** Static port counter to avoid port-reuse race conditions between sequential tests */
    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(12100);

    private NetManager neta;
    private int        port;

    private static int findFreePort() {
        return PORT_COUNTER.getAndIncrement();
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

    private static ByteBuf toBody(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() + 16);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return buf;
    }

    @Before
    public void setUp() throws Exception {
        Thread.sleep(50);
        port = findFreePort();
        neta = new NetManager();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    // ========================= GET Request =========================

    @Test
    public void testGetRequest_200OK() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("Hello from Neta!");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain; charset=UTF-8");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/test").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        assertEquals("text/plain; charset=UTF-8", conn.getHeaderField("Content-Type"));
        assertEquals("Hello from Neta!", readResponse(conn));
        conn.disconnect();
    }

    // ========================= POST Request with JSON Body =========================

    @Test
    public void testPostRequest_JSONBody() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf reqContent = request.content();
            String reqBody = reqContent.readString(reqContent.readableBytes(), StandardCharsets.UTF_8);
            String escapedBody = reqBody.replace("\"", "\\\"");

            ByteBuf body = toBody("{\"echo\":\"" + escapedBody + "\"}");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "application/json");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/json").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        String sendBody = "{\"key\":\"value\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sendBody.getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, conn.getResponseCode());
        String resp = readResponse(conn);
        assertTrue(resp.contains("\"echo\":\"{\\\"key\\\":\\\"value\\\"}\""));
        conn.disconnect();
    }

    // ========================= 404 Not Found =========================

    @Test
    public void testNotFound_404() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("Not Found");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NOT_FOUND, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/missing").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(404, conn.getResponseCode());
        assertEquals("Not Found", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Multiple HTTP Methods =========================

    @Test
    public void testPutRequest() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("method=" + request.method().name());
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/resource").openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("update-data".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals("method=PUT", readResponse(conn));
        conn.disconnect();
    }

    @Test
    public void testDeleteRequest() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("deleted");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/resource/123").openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("deleted", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Custom Headers =========================

    @Test
    public void testCustomHeaders() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            String xToken = request.headers().get("X-Custom-Token");

            ByteBuf body = toBody("token=" + xToken);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            response.headers().set("X-Response-Custom", "neta-value");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/headers").openConnection();
        conn.setRequestProperty("X-Custom-Token", "abc123");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("neta-value", conn.getHeaderField("X-Response-Custom"));
        assertEquals("token=abc123", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Status Codes =========================

    @Test
    public void testStatusCode201Created() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody("{\"id\":42}");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.CREATED, body);
            response.headers().set("Content-Type", "application/json");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            response.headers().set("Location", "/resource/42");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/create").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("data".getBytes());
        }
        assertEquals(201, conn.getResponseCode());
        assertEquals("/resource/42", conn.getHeaderField("Location"));
        conn.disconnect();
    }

    @Test
    public void testStatusCode204NoContent() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.NO_CONTENT);
            response.headers().set("Content-Length", "0");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/no-content").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(204, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void testStatusCode500InternalError() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody("Internal Server Error");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.INTERNAL_SERVER_ERROR, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/error").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(500, conn.getResponseCode());
        assertEquals("Internal Server Error", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Large Body =========================

    @Test
    public void testLargeResponseBody() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Line-").append(i).append(": This is line data for testing large response body.\n");
        }
        final String largeBody = sb.toString();

        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody(largeBody);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/large").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        String resp = readResponse(conn);
        assertEquals(largeBody, resp);
        conn.disconnect();
    }

    @Test
    public void testLargeRequestBody() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("request-data-line-").append(i).append("\n");
        }
        final String largeRequest = sb.toString();

        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf reqContent = request.content();
            int len = reqContent.readableBytes();

            ByteBuf body = toBody("received=" + len);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        byte[] bodyBytes = largeRequest.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        assertEquals(200, conn.getResponseCode());
        String resp = readResponse(conn);
        assertEquals("received=" + bodyBytes.length, resp);
        conn.disconnect();
    }

    // ========================= Keep-Alive Multiple Requests =========================

    @Test
    public void testKeepAliveMultipleRequests() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("uri=" + request.uri());
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/plain");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        // Send multiple requests over the same connection (keep-alive)
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/path" + i).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            assertEquals(200, conn.getResponseCode());
            assertEquals("uri=/path" + i, readResponse(conn));
            conn.disconnect();
        }
    }

    // ========================= URI Variations =========================

    @Test
    public void testUriWithQueryParameters() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("uri=" + request.uri());
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/search?q=neta&page=1").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("uri=/search?q=neta&page=1", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Empty Body Response =========================

    @Test
    public void testEmptyBodyResponse() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK);
            response.headers().set("Content-Length", "0");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/empty").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("", readResponse(conn));
        conn.disconnect();
    }

    // ========================= Multiple Content Types =========================

    @Test
    public void testHtmlResponse() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            String html = "<html><body><h1>Hello</h1></body></html>";
            ByteBuf body = toBody(html);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, body);
            response.headers().set("Content-Type", "text/html; charset=UTF-8");
            response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(200);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/page").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/html; charset=UTF-8", conn.getHeaderField("Content-Type"));
        assertTrue(readResponse(conn).contains("<h1>Hello</h1>"));
        conn.disconnect();
    }
}
