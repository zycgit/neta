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
package net.hasor.neta.codec.http.h2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import okhttp3.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP/2 protocol verification tests using OkHttp as external HTTP/2 client.
 * <p>
 * Starts a real Neta TCP server with h2c (HTTP/2 cleartext, Prior Knowledge)
 * detection via {@link ProtoRoutingDuplexer}, then uses OkHttp with
 * {@link Protocol#H2_PRIOR_KNOWLEDGE} to send genuine HTTP/2 frames.
 * <p>
 * This verifies end-to-end HTTP/2 binary framing and HPACK header compression
 * over real TCP sockets with a third-party HTTP/2 client library.
 */
public class Http2OkHttpProtocolTest {

    private static class H2RouteState {
        private boolean h2PriorKnowledge;
    }

    private NetManager   neta;
    private int          port;
    private OkHttpClient h2Client;

    /** Per-test request handler. Set before starting the server or sending requests. */
    private volatile Consumer<RequestContext> requestHandler;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
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

        // OkHttp client configured for h2c Prior Knowledge (cleartext HTTP/2)
        h2Client = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE)).build();
    }

    @After
    public void tearDown() throws IOException {
        if (h2Client != null) {
            h2Client.dispatcher().executorService().shutdown();
            h2Client.connectionPool().evictAll();
        }
        if (neta != null) {
            neta.shutdown();
        }
    }

    /**
     * Starts a neta server with h2c (HTTP/2 cleartext) detection using ProtoRoutingDuplexer.
     * The route is selected by peeking the first 4 bytes for the HTTP/2 preface "PRI ".
     * <p>
     * Includes an inline dispatch handler that sends responses during RCV processing
     * (same as the real server's HttpDispatchHandler), ensuring SETTINGS is always
     * the first frame on the wire.
     */
    private void startH2cServer() throws Exception {
        this.startH2cServer(65535);
    }

    private void startH2cServer(int initialWindowSize) throws Exception {
        Http2OkHttpProtocolTest self = this;
        ProtoInitializer serverProto = ctx -> {
            ProtoRoutingDuplexer<ByteBuf, ByteBuf> detect = new ProtoRoutingDuplexer<>((context, rcvUp, sndDown) -> {
                H2RouteState routeState = context.context(H2RouteState.class);
                if (routeState != null && routeState.h2PriorKnowledge) {
                    return "h2";
                }
                if (rcvUp == null) {
                    return null;
                }
                ByteBuf first = rcvUp.peekMessage();
                if (first == null || first.readableBytes() < 4) {
                    return null;
                }
                if ((first.getByte(0) & 0xFF) == 0x50
                        && (first.getByte(1) & 0xFF) == 0x52
                        && (first.getByte(2) & 0xFF) == 0x49
                        && (first.getByte(3) & 0xFF) == 0x20) {
                    if (routeState == null) {
                        routeState = new H2RouteState();
                        context.context(H2RouteState.class, routeState);
                    }
                    routeState.h2PriorKnowledge = true;
                    return "h2";
                }
                return "http";
            });

            detect.addBranch("h2", h2cBranch -> {
                h2cBranch.addLast("h2-codec", new Http2ServerDuplexe(4096, 8192, initialWindowSize));
                h2cBranch.addLastDecoder("h2-http-dec", new Http2MessageToHttpDecoder());
                h2cBranch.addLastEncoder("h2-http-enc", new Http2HttpToMessageEncoder(true));
                h2cBranch.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576));
                // Inline dispatch handler — sends response during RCV processing,
                // matching real server behavior (HttpDispatchHandler in nhttp).
                h2cBranch.addLastDecoder("h2-handler", new InlineDispatchHandler(self));
            });

            detect.addBranch("http", httpBranch -> {
                httpBranch.addLast("http-codec", new HttpServerDuplexe());
                httpBranch.addLast("http-aggregator", new HttpServerDuplexeAggregator(1048576));
            });

            ctx.addLast("protocol-detect", detect);
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
    }

    @Test
    public void testH2c_GetRequest_ProtocolVerification() throws Exception {
        requestHandler = ctx -> {
            ByteBuf body = toBody("Hello HTTP/2!");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/index").get().build();
        Response response = h2Client.newCall(request).execute();

        // *** Critical: verify the protocol is genuinely HTTP/2 ***
        assertEquals("Protocol must be h2_prior_knowledge (HTTP/2 cleartext)", Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals("Hello HTTP/2!", response.body().string());
        response.close();
    }

    @Test
    public void testH2c_PostWithJsonBody() throws Exception {
        requestHandler = ctx -> {
            assertEquals(HttpMethod.POST, ctx.request.method());

            ByteBuf content = ctx.request.content();
            String reqBody = content.readString(content.readableBytes(), StandardCharsets.UTF_8);
            ByteBuf respBody = toBody("{\"received\":" + reqBody.length() + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, respBody);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(respBody.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        RequestBody body = RequestBody.create("{\"key\":\"value\"}", MediaType.get("application/json"));
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/api/data").post(body).build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        String respBody = response.body().string();
        assertTrue(respBody.contains("\"received\":15"));
        response.close();
    }

    // ========================= GET — verify HTTP/2 protocol =========================

    @Test
    public void testH2c_CustomHeadersRoundTrip() throws Exception {
        requestHandler = ctx -> {
            String reqId = ctx.request.getString("x-request-id");
            String agent = ctx.request.getString("user-agent");

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
            response.setHeader("x-echo-request-id", reqId != null ? reqId : "unknown");
            response.setHeader("x-echo-user-agent", agent != null ? agent : "unknown");
            response.setHeader("x-server", "neta-h2c");
            response.setHeader("content-length", "0");
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/headers").header("X-Request-Id", "h2-test-42").header("User-Agent", "OkHttp-H2-Test/1.0").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals("h2-test-42", response.header("x-echo-request-id"));
        assertEquals("neta-h2c", response.header("x-server"));
        response.close();
    }

    // ========================= POST with JSON body =========================

    @Test
    public void testH2c_StatusCode404() throws Exception {
        requestHandler = ctx -> {
            ByteBuf body = toBody("Not Found");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_FOUND, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/missing").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(404, response.code());
        assertEquals("Not Found", response.body().string());
        response.close();
    }

    // ========================= Custom headers round-trip =========================

    @Test
    public void testH2c_StatusCode500() throws Exception {
        requestHandler = ctx -> {
            ByteBuf body = toBody("Internal Server Error");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.INTERNAL_SERVER_ERROR, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/error").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(500, response.code());
        response.close();
    }

    // ========================= Status code 404 =========================

    @Test
    public void testH2c_PutRequest() throws Exception {
        requestHandler = ctx -> {
            ByteBuf body = toBody("{\"updated\":true}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        RequestBody body = RequestBody.create("{\"name\":\"updated\"}", MediaType.get("application/json"));
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/1").put(body).build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertTrue(response.body().string().contains("\"updated\":true"));
        response.close();
    }

    // ========================= Status code 500 =========================

    @Test
    public void testH2c_DeleteRequest() throws Exception {
        requestHandler = ctx -> {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
            response.setHeader("content-length", "0");
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/42").delete().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(204, response.code());
        response.close();
    }

    // ========================= PUT request =========================

    @Test
    public void testH2c_LargeResponseBody() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": HTTP/2 h2c large body test payload data.\n");
        }
        final String largeBody = sb.toString();

        requestHandler = ctx -> {
            ByteBuf body = toBody(largeBody);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/large").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals(largeBody, response.body().string());
        response.close();
    }

    // ========================= DELETE request =========================

    @Test
    public void testH2c_MultipleSequentialRequests() throws Exception {
        requestHandler = ctx -> {
            String uri = ctx.request.uri();
            ByteBuf body = toBody("Response for " + uri);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        // Send 3 requests through the same h2c client.
        for (int i = 0; i < 3; i++) {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/req/" + i).get().build();
            Response response = h2Client.newCall(request).execute();

            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
            assertEquals(200, response.code());
            assertEquals("Response for /req/" + i, response.body().string());
            response.close();
            h2Client.connectionPool().evictAll();
        }
    }

    // ========================= Large body =========================

    @Test
    public void testH2c_PostLargeRequestBody() throws Exception {
        requestHandler = ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer();
        Thread.sleep(300);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("data-line-").append(i).append(": OkHttp h2c request body test.\n");
        }
        String largeReqBody = sb.toString();
        RequestBody reqBody = RequestBody.create(largeReqBody, MediaType.get("text/plain"));
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload").post(reqBody).build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        String respBody = response.body().string();
        assertTrue("Body size should be reported", respBody.contains("\"bodySize\":" + largeReqBody.length()));
        response.close();
    }

    // ========================= Multiple sequential requests =========================

    /**
     * Sends a 100KB request body through the real h2c stack.
     * The test server advertises a larger initial window so the request can complete
     * without depending on multiple WINDOW_UPDATE exchanges in this integration setup.
     */
    @Test
    public void testH2c_PostExceedsInitialWindow_100KB() throws Exception {
        requestHandler = ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer(256 * 1024);
        Thread.sleep(300);

        byte[] largeData = new byte[100 * 1024]; // 102400 bytes
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) ('A' + (i % 26));
        }
        RequestBody reqBody = RequestBody.create(largeData, MediaType.get("application/octet-stream"));

        OkHttpClient timedClient = h2Client.newBuilder().readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload-large").post(reqBody).build();
        Response response = timedClient.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        String respBody = response.body().string();
        assertTrue("Server should receive all 102400 bytes", respBody.contains("\"bodySize\":102400"));
        response.close();
    }

    // ========================= POST large request body =========================

    /**
     * Sends a 200KB request body through the real h2c stack with an expanded
     * server initial window, covering larger upload framing over a single connection.
     */
    @Test
    public void testH2c_PostExceedsInitialWindow_200KB() throws Exception {
        requestHandler = ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        };
        startH2cServer(256 * 1024);
        Thread.sleep(300);

        byte[] largeData = new byte[200 * 1024]; // 204800 bytes — needs 3+ rounds of WINDOW_UPDATE
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) ('0' + (i % 10));
        }
        RequestBody reqBody = RequestBody.create(largeData, MediaType.get("application/octet-stream"));

        OkHttpClient timedClient = h2Client.newBuilder().readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload-large").post(reqBody).build();
        Response response = timedClient.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        String respBody = response.body().string();
        assertTrue("Server should receive all 204800 bytes", respBody.contains("\"bodySize\":204800"));
        response.close();
    }

    // ========================= POST body exceeding initial flow control window =========================

    /** Context passed to the per-test request handler. */
    static class RequestContext {
        final FullHttpRequest request;
        final ProtoContext    context;

        RequestContext(FullHttpRequest request, ProtoContext context) {
            this.request = request;
            this.context = context;
        }

        void send(FullHttpResponse response) {
            if (response.streamId() <= 0) {
                response.streamId(this.request.streamId());
            }
            try {
                this.context.sendData(response).get();
            } catch (Throwable e) {
                throw new IllegalStateException("failed to send h2 test response", e);
            }
        }
    }

    /**
     * Inline handler that dispatches requests during RCV processing.
     * This ensures sendData() is called within the handler's onMessage(isRcv=true),
     * so the SETTINGS + response are encoded together in the same SND pass.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static class InlineDispatchHandler implements ProtoHandler<HttpObject, Object> {
        private final Http2OkHttpProtocolTest test;

        InlineDispatchHandler(Http2OkHttpProtocolTest test) {
            this.test = test;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                Object msg = ((ProtoRcvQueue) src).takeMessage();
                if (msg instanceof FullHttpRequest && test.requestHandler != null) {
                    test.requestHandler.accept(new RequestContext((FullHttpRequest) msg, context));
                }
            }
            return ProtoStatus.Next;
        }
    }
}
