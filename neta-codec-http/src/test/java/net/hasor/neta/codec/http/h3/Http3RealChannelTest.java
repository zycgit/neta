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
package net.hasor.neta.codec.http.h3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP/3 bidirectional integration tests using Virtual Channel (neta-to-neta).
 * <p>
 * Simulates real client-server communication with HTTP/3 codec on both sides.
 * Uses {@link VrtChannel} + {@link VrtTransfer} to connect Http3ClientDuplexe and Http3ServerDuplexe.
 */
public class Http3RealChannelTest {

    private static ByteBuf toBody(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() + 16);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return buf;
    }

    private static String readBody(ByteBuf buf) {
        if (buf == null || buf.readableBytes() == 0)
            return "";
        return buf.readString(buf.readableBytes(), StandardCharsets.UTF_8);
    }

    // ========================= Basic GET Request =========================

    @Test
    public void testGetRequest() throws Throwable {
        NetManager neta = new NetManager();

        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());

        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Client sends GET
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/index");
        request.headers().add("host", "www.example.com");
        client.sendData(request).get();

        // Verify server received request
        assertTrue("Server should receive data", serverRcv.size() >= 1);
        Object req = serverRcv.poll();
        assertTrue("Should be FullHttpRequest", req instanceof FullHttpRequest);
        FullHttpRequest decodedReq = (FullHttpRequest) req;
        assertEquals(HttpMethod.GET, decodedReq.method());
        assertEquals("/index", decodedReq.uri());

        // Server responds
        ByteBuf respBody = toBody("Hello HTTP/3");
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK, respBody);
        response.headers().set("content-type", "text/plain");
        server.sendData(response).get();

        // Verify client received response
        assertTrue("Client should receive data", clientRcv.size() >= 1);
        Object resp = clientRcv.poll();
        assertTrue("Should be FullHttpResponse", resp instanceof FullHttpResponse);
        FullHttpResponse decodedResp = (FullHttpResponse) resp;
        assertEquals(200, decodedResp.status().code());
        assertEquals("Hello HTTP/3", readBody(decodedResp.content()));

        neta.shutdown();
    }

    // ========================= POST Request with Body =========================

    @Test
    public void testPostWithJsonBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Client sends POST with JSON
        ByteBuf reqBody = toBody("{\"user\":\"neta\",\"version\":3}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/api/users", reqBody);
        request.headers().add("host", "api.example.com");
        request.headers().add("content-type", "application/json");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.POST, decodedReq.method());
        assertEquals("/api/users", decodedReq.uri());
        assertEquals("{\"user\":\"neta\",\"version\":3}", readBody(decodedReq.content()));

        // Server responds with 201
        ByteBuf respBody = toBody("{\"id\":1}");
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.CREATED, respBody);
        response.headers().set("content-type", "application/json");
        server.sendData(response).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decodedResp = (FullHttpResponse) clientRcv.poll();
        assertEquals(201, decodedResp.status().code());
        assertEquals("{\"id\":1}", readBody(decodedResp.content()));

        neta.shutdown();
    }

    // ========================= DELETE Request =========================

    @Test
    public void testDeleteRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.DELETE, "/items/42");
        request.headers().add("host", "api.example.com");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.DELETE, decoded.method());
        assertEquals("/items/42", decoded.uri());

        neta.shutdown();
    }

    // ========================= PUT Request =========================

    @Test
    public void testPutRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        ByteBuf body = toBody("{\"name\":\"updated\"}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.PUT, "/items/7", body);
        request.headers().add("host", "api.example.com");
        request.headers().add("content-type", "application/json");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.PUT, decoded.method());
        assertEquals("/items/7", decoded.uri());
        assertEquals("{\"name\":\"updated\"}", readBody(decoded.content()));

        neta.shutdown();
    }

    // ========================= Status Code 404 =========================

    @Test
    public void testStatusCode404() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/not-found");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll();
        ByteBuf body = toBody("Not Found");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NOT_FOUND, body);
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decoded = (FullHttpResponse) clientRcv.poll();
        assertEquals(404, decoded.status().code());
        assertEquals("Not Found", readBody(decoded.content()));

        neta.shutdown();
    }

    // ========================= Status Code 500 =========================

    @Test
    public void testStatusCode500() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/error");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll();
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.INTERNAL_SERVER_ERROR);
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decoded = (FullHttpResponse) clientRcv.poll();
        assertEquals(500, decoded.status().code());

        neta.shutdown();
    }

    // ========================= Custom Headers Round-Trip =========================

    @Test
    public void testCustomHeadersRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Request with custom headers
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.GET, "/custom");
        req.headers().add("host", "example.com");
        req.headers().add("x-request-id", "req-h3-001");
        req.headers().add("accept", "application/json");
        req.headers().add("user-agent", "neta/3.0");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals("req-h3-001", decodedReq.headers().get("x-request-id"));
        assertEquals("application/json", decodedReq.headers().get("accept"));
        assertEquals("neta/3.0", decodedReq.headers().get("user-agent"));

        // Response with custom headers
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
        resp.headers().set("x-response-id", "resp-h3-002");
        resp.headers().set("cache-control", "no-store");
        resp.headers().set("x-frame-options", "DENY");
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decodedResp = (FullHttpResponse) clientRcv.poll();
        assertEquals("resp-h3-002", decodedResp.headers().get("x-response-id"));
        assertEquals("no-store", decodedResp.headers().get("cache-control"));
        assertEquals("DENY", decodedResp.headers().get("x-frame-options"));

        neta.shutdown();
    }

    // ========================= Large Body =========================

    @Test
    public void testLargeBodyRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        // 8KB body
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": HTTP/3 large body payload test data.\n");
        }
        String largeBody = sb.toString();

        ByteBuf body = toBody(largeBody);
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.POST, "/upload", body);
        req.headers().add("host", "upload.example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals(largeBody, readBody(decoded.content()));

        neta.shutdown();
    }

    // ========================= No Body 204 Response =========================

    @Test
    public void testNoBodyResponse204() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.DELETE, "/item/99");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll();
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.NO_CONTENT);
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decoded = (FullHttpResponse) clientRcv.poll();
        assertEquals(204, decoded.status().code());

        neta.shutdown();
    }

    // ========================= PATCH Method =========================

    @Test
    public void testPatchRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        ByteBuf body = toBody("{\"field\":\"patched\"}");
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.PATCH, "/item/5", body);
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.PATCH, decoded.method());
        assertEquals("{\"field\":\"patched\"}", readBody(decoded.content()));

        neta.shutdown();
    }

    // ========================= HEAD Method =========================

    @Test
    public void testHeadRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.HEAD, "/resource");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.HEAD, decoded.method());
        assertEquals("/resource", decoded.uri());

        neta.shutdown();
    }

    // ========================= OPTIONS Method =========================

    @Test
    public void testOptionsRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h3", new Http3ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h3", new Http3ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_3_0, HttpMethod.OPTIONS, "*");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.OPTIONS, decoded.method());

        // Server responds with CORS headers
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_3_0, HttpStatus.OK);
        resp.headers().set("access-control-allow-origin", "*");
        resp.headers().set("access-control-allow-methods", "GET, POST, DELETE");
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decodedResp = (FullHttpResponse) clientRcv.poll();
        assertEquals("*", decodedResp.headers().get("access-control-allow-origin"));

        neta.shutdown();
    }
}
