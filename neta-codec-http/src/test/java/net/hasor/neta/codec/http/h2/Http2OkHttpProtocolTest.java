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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.Http2PrefaceRouting;
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

    private NetManager   neta;
    private int          port;
    private OkHttpClient h2Client;

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
     * Uses {@link Http2PrefaceRouting} to detect "PRI " prefix (HTTP/2 connection preface)
     * and route to Http2ServerDuplexe.
     */
    private void startH2cServer() throws Exception {
        ProtoInitializer serverProto = ctx -> {
            Http2PrefaceRouting routing = new Http2PrefaceRouting("http");
            ProtoRoutingDuplexer.Builder<ByteBuf> detect = ProtoRoutingDuplexer.newBuilder(routing);

            detect.branch(Http2PrefaceRouting.BRANCH_H2C, h2cBranch -> {
                h2cBranch.addLast("h2-codec", new Http2ServerDuplexe());
                h2cBranch.addLastDecoder("h2-aggregator", new HttpObjectAggregator(1048576));
            });

            detect.branch("http", httpBranch -> {
                httpBranch.addLast("http-codec", new HttpServerDuplexe());
                httpBranch.addLastDecoder("http-aggregator", new HttpObjectAggregator(1048576));
            });

            ctx.addLast("protocol-detect", detect.build(ctx));
        };
        neta.bind(new InetSocketAddress("0.0.0.0", port), serverProto, SoConfig.TCP());
    }

    // ========================= GET — verify HTTP/2 protocol =========================

    @Test
    public void testH2c_GetRequest_ProtocolVerification() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("Hello HTTP/2!");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "text/plain");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/index").get().build();
        Response response = h2Client.newCall(request).execute();

        // *** Critical: verify the protocol is genuinely HTTP/2 ***
        assertEquals("Protocol must be h2_prior_knowledge (HTTP/2 cleartext)", Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals("Hello HTTP/2!", response.body().string());
        response.close();
    }

    // ========================= POST with JSON body =========================

    @Test
    public void testH2c_PostWithJsonBody() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            assertEquals(HttpMethod.POST, request.method());

            ByteBuf content = request.content();
            String reqBody = content.readString(content.readableBytes(), StandardCharsets.UTF_8);
            ByteBuf respBody = toBody("{\"received\":" + reqBody.length() + "}");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, respBody);
            response.headers().set("content-type", "application/json");
            response.headers().set("content-length", String.valueOf(respBody.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
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

    // ========================= Custom headers round-trip =========================

    @Test
    public void testH2c_CustomHeadersRoundTrip() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            String reqId = request.headers().get("x-request-id");
            String agent = request.headers().get("user-agent");

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
            response.headers().set("x-echo-request-id", reqId != null ? reqId : "unknown");
            response.headers().set("x-echo-user-agent", agent != null ? agent : "unknown");
            response.headers().set("x-server", "neta-h2c");
            response.headers().set("content-length", "0");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/headers").header("X-Request-Id", "h2-test-42").header("User-Agent", "OkHttp-H2-Test/1.0").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals("h2-test-42", response.header("x-echo-request-id"));
        assertEquals("neta-h2c", response.header("x-server"));
        response.close();
    }

    // ========================= Status code 404 =========================

    @Test
    public void testH2c_StatusCode404() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody("Not Found");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_FOUND, body);
            response.headers().set("content-type", "text/plain");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/missing").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(404, response.code());
        assertEquals("Not Found", response.body().string());
        response.close();
    }

    // ========================= Status code 500 =========================

    @Test
    public void testH2c_StatusCode500() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody("Internal Server Error");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.INTERNAL_SERVER_ERROR, body);
            response.headers().set("content-type", "text/plain");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/error").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(500, response.code());
        response.close();
    }

    // ========================= PUT request =========================

    @Test
    public void testH2c_PutRequest() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf body = toBody("{\"updated\":true}");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "application/json");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        RequestBody body = RequestBody.create("{\"name\":\"updated\"}", MediaType.get("application/json"));
        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/1").put(body).build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertTrue(response.body().string().contains("\"updated\":true"));
        response.close();
    }

    // ========================= DELETE request =========================

    @Test
    public void testH2c_DeleteRequest() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
            response.headers().set("content-length", "0");
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/item/42").delete().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(204, response.code());
        response.close();
    }

    // ========================= Large body =========================

    @Test
    public void testH2c_LargeResponseBody() throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": HTTP/2 h2c large body test payload data.\n");
        }
        final String largeBody = sb.toString();

        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            ByteBuf body = toBody(largeBody);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "text/plain");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/large").get().build();
        Response response = h2Client.newCall(request).execute();

        assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
        assertEquals(200, response.code());
        assertEquals(largeBody, response.body().string());
        response.close();
    }

    // ========================= Multiple sequential requests =========================

    @Test
    public void testH2c_MultipleRequestsOnSameConnection() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            String uri = request.uri();
            ByteBuf body = toBody("Response for " + uri);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "text/plain");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
        Thread.sleep(300);

        // Send 3 requests on the same h2c connection
        for (int i = 0; i < 3; i++) {
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/req/" + i).get().build();
            Response response = h2Client.newCall(request).execute();

            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
            assertEquals(200, response.code());
            assertEquals("Response for /req/" + i, response.body().string());
            response.close();
        }
    }

    // ========================= POST large request body =========================

    @Test
    public void testH2c_PostLargeRequestBody() throws Exception {
        startH2cServer();
        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            FullHttpRequest request = (FullHttpRequest) payload.getData();
            ByteBuf content = request.content();
            int size = content != null ? content.readableBytes() : 0;
            ByteBuf body = toBody("{\"bodySize\":" + size + "}");
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, body);
            response.headers().set("content-type", "application/json");
            response.headers().set("content-length", String.valueOf(body.readableBytes()));
            ((NetChannel) payload.getSource()).sendData(response);
        });
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
}
