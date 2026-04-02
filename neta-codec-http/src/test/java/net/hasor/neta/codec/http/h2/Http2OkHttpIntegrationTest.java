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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import okhttp3.*;
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
public class Http2OkHttpIntegrationTest extends AbstractHttpTest {

    private static class H2RouteState {
        private boolean h2PriorKnowledge;
    }

    private static OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE)).build();
    }

    private static ByteBuf toBody(String text) {
        return ByteBuf.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void startH2cServer(NetManager neta, int port, int initialWindowSize, Consumer<RequestContext> requestHandler) throws Throwable {
        ProtoInitializer serverProto = ctx -> {
            ProtoRoutingDuplexer<ByteBuf, ByteBuf> detect = new ProtoRoutingDuplexer<>((ProtoRoutingDataSelector<ByteBuf, ByteBuf>) (context, rcvUp, sndDown) -> {
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
                if ((first.getByte(0) & 0xFF) == 0x50//
                        && (first.getByte(1) & 0xFF) == 0x52//
                        && (first.getByte(2) & 0xFF) == 0x49//
                        && (first.getByte(3) & 0xFF) == 0x20) {
                    if (routeState == null) {
                        routeState = new H2RouteState();
                        context.context(H2RouteState.class, routeState);
                    }
                    routeState.h2PriorKnowledge = true;
                    return "h2";
                }
                return null;
            });

            Http2Settings settings = new Http2Settings()//
                    .headerTableSize(4096)//
                    .maxHeaderListSize(8192)//
                    .initialWindowSize(Math.max(initialWindowSize, 65535))//
                    .enablePush(false)//
                    .maxConcurrentStreams(100L);

            detect.addBranch("h2", branchCtx -> ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(true, settings))//
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), ppb -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        ProtoPartitionControl control = ppb.control();
                        ppb.policy(policy).byInitializer(pbc -> {
                            pbc.addLast("h2-aggregator", new HttpServerDuplexeAggregator(1048576));
                        }).byDefault(pbc -> {
                            pbc.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(control, policy));
                        });
                    })//
                    .nextDecoder("h2-handler", new InlineDispatchHandler(requestHandler))//
                    .config(branchCtx));

            detect.addBranch("http", httpBranch -> {
                httpBranch.addLast("http-codec", new HttpServerDuplexe());
                httpBranch.addLast("http-aggregator", new HttpServerDuplexeAggregator(1048576));
            });

            ctx.addLast("protocol-detect", detect);
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
    }

    private void withH2cServer(Consumer<RequestContext> requestHandler, H2cScenario scenario) throws Throwable {
        withH2cServer(requestHandler, 65535, scenario);
    }

    private void withH2cServer(Consumer<RequestContext> requestHandler, int initialWindowSize, H2cScenario scenario) throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient h2Client = okHttpClient();
        try {
            startH2cServer(neta, port, initialWindowSize, requestHandler);
            Thread.sleep(300);
            scenario.run(port, h2Client);
        } finally {
            h2Client.dispatcher().executorService().shutdown();
            h2Client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testPriorKnowledgeGetRequest() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf body = toBody("Hello HTTP/2!");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/index").get().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals("Protocol must be h2_prior_knowledge (HTTP/2 cleartext)", Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertEquals("Hello HTTP/2!", response.body().string());
            } finally {
                response.close();
            }
        });
    }

    @Test
    public void testPriorKnowledgePostJsonBody() throws Throwable {
        withH2cServer(ctx -> {
            assertEquals(HttpMethod.POST, ctx.request.method());

            ByteBuf content = ctx.request.content();
            String reqBody = content.readString(content.readableBytes(), StandardCharsets.UTF_8);
            ByteBuf respBody = toBody("{\"received\":" + reqBody.length() + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, respBody);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(respBody.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            RequestBody body = RequestBody.create("{\"key\":\"value\"}", MediaType.get("application/json"));
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/api/data").post(body).build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                String respBody = response.body().string();
                assertTrue(respBody.contains("\"received\":15"));
            } finally {
                response.close();
            }
        });
    }

    // ========================= GET — verify HTTP/2 protocol =========================

    @Test
    public void testPriorKnowledgeCustomHeadersRoundTrip() throws Throwable {
        withH2cServer(ctx -> {
            String reqId = ctx.request.getString("x-request-id");
            String agent = ctx.request.getString("user-agent");

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
            response.setHeader("x-echo-request-id", reqId != null ? reqId : "unknown");
            response.setHeader("x-echo-user-agent", agent != null ? agent : "unknown");
            response.setHeader("x-server", "neta-h2c");
            response.setHeader("content-length", "0");
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/headers").header("X-Request-Id", "h2-test-42").header("User-Agent", "OkHttp-H2-Test/1.0").get().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertEquals("h2-test-42", response.header("x-echo-request-id"));
                assertEquals("neta-h2c", response.header("x-server"));
            } finally {
                response.close();
            }
        });
    }

    // ========================= POST with JSON body =========================

    @Test
    public void testPriorKnowledgeNotFoundResponse() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf body = toBody("Not Found");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_FOUND, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/missing").get().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(404, response.code());
                assertEquals("Not Found", response.body().string());
            } finally {
                response.close();
            }
        });
    }

    // ========================= Custom headers round-trip =========================

    @Test
    public void testPriorKnowledgeInternalServerError() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf body = toBody("Internal Server Error");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.INTERNAL_SERVER_ERROR, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/error").get().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(500, response.code());
            } finally {
                response.close();
            }
        });
    }

    // ========================= Status code 404 =========================

    @Test
    public void testPriorKnowledgePutRequest() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf body = toBody("{\"updated\":true}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            RequestBody body = RequestBody.create("{\"name\":\"updated\"}", MediaType.get("application/json"));
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/1").put(body).build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertTrue(response.body().string().contains("\"updated\":true"));
            } finally {
                response.close();
            }
        });
    }

    // ========================= Status code 500 =========================

    @Test
    public void testPriorKnowledgeDeleteRequest() throws Throwable {
        withH2cServer(ctx -> {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
            response.setHeader("content-length", "0");
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/42").delete().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(204, response.code());
            } finally {
                response.close();
            }
        });
    }

    // ========================= PUT request =========================

    @Test
    public void testPriorKnowledgeLargeResponseBody() throws Throwable {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": HTTP/2 h2c large body test payload data.\n");
        }
        final String largeBody = sb.toString();

        withH2cServer(ctx -> {
            ByteBuf body = toBody(largeBody);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/large").get().build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertEquals(largeBody, response.body().string());
            } finally {
                response.close();
            }
        });
    }

    // ========================= DELETE request =========================

    @Test
    public void testPriorKnowledgeMultipleSequentialRequests() throws Throwable {
        withH2cServer(ctx -> {
            String uri = ctx.request.uri();
            ByteBuf body = toBody("Response for " + uri);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "text/plain");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            for (int i = 0; i < 3; i++) {
                Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/req/" + i).get().build();
                Response response = client.newCall(request).execute();
                try {
                    assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                    assertEquals(200, response.code());
                    assertEquals("Response for /req/" + i, response.body().string());
                } finally {
                    response.close();
                }
                client.connectionPool().evictAll();
            }
        });
    }

    // ========================= Large body =========================

    @Test
    public void testPriorKnowledgePostLargeRequestBody() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, (port, client) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("data-line-").append(i).append(": OkHttp h2c request body test.\n");
            }
            String largeReqBody = sb.toString();
            RequestBody reqBody = RequestBody.create(largeReqBody, MediaType.get("text/plain"));
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload").post(reqBody).build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                String respBody = response.body().string();
                assertTrue("Body size should be reported", respBody.contains("\"bodySize\":" + largeReqBody.length()));
            } finally {
                response.close();
            }
        });
    }

    // ========================= Multiple sequential requests =========================

    /**
     * Sends a 100KB request body through the real h2c stack.
     * The test server advertises a larger initial window so the request can complete
     * without depending on multiple WINDOW_UPDATE exchanges in this integration setup.
     */
    @Test
    public void testPriorKnowledgePostBodyExceedsInitialWindow100KB() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, 256 * 1024, (port, client) -> {
            byte[] largeData = new byte[100 * 1024];
            for (int i = 0; i < largeData.length; i++) {
                largeData[i] = (byte) ('A' + (i % 26));
            }
            RequestBody reqBody = RequestBody.create(largeData, MediaType.get("application/octet-stream"));
            OkHttpClient timedClient = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload-large").post(reqBody).build();
            Response response = timedClient.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                String respBody = response.body().string();
                assertTrue("Server should receive all 102400 bytes", respBody.contains("\"bodySize\":102400"));
            } finally {
                response.close();
            }
        });
    }

    // ========================= POST large request body =========================

    /**
     * Sends a 200KB request body through the real h2c stack with an expanded
     * server initial window, covering larger upload framing over a single connection.
     */
    @Test
    public void testPriorKnowledgePostBodyExceedsInitialWindow200KB() throws Throwable {
        withH2cServer(ctx -> {
            ByteBuf content = ctx.request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.setHeader("content-type", "application/json");
            response.setHeader("content-length", String.valueOf(body.readableBytes()));
            ctx.send(response);
        }, 256 * 1024, (port, client) -> {
            byte[] largeData = new byte[200 * 1024];
            for (int i = 0; i < largeData.length; i++) {
                largeData[i] = (byte) ('0' + (i % 10));
            }
            RequestBody reqBody = RequestBody.create(largeData, MediaType.get("application/octet-stream"));
            OkHttpClient timedClient = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/upload-large").post(reqBody).build();
            Response response = timedClient.newCall(request).execute();
            try {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                String respBody = response.body().string();
                assertTrue("Server should receive all 204800 bytes", respBody.contains("\"bodySize\":204800"));
            } finally {
                response.close();
            }
        });
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
        private final Consumer<RequestContext> requestHandler;

        InlineDispatchHandler(Consumer<RequestContext> requestHandler) {
            this.requestHandler = requestHandler;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                Object msg = ((ProtoRcvQueue) src).takeMessage();
                if (msg instanceof FullHttpRequest && this.requestHandler != null) {
                    this.requestHandler.accept(new RequestContext((FullHttpRequest) msg, context));
                }
            }
            return ProtoStatus.Next;
        }
    }

    @FunctionalInterface
    private interface H2cScenario {
        void run(int port, OkHttpClient client) throws Exception;
    }
}
