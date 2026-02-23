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
 * HTTP/2 bidirectional integration tests using Virtual Channel (neta-to-neta).
 * <p>
 * Simulates real client-server communication with HTTP/2 codec on both sides.
 * Uses {@link VrtChannel} + {@link VrtTransfer} to connect Http2ClientDuplexe and Http2ServerDuplexe.
 */
public class Http2RealChannelTest {

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

    // ========================= Basic Request-Response =========================

    @Test
    public void testGetRequestResponse() throws Throwable {
        NetManager neta = new NetManager();

        // Server side: HTTP/2 server codec + aggregator
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());

        // Client side: HTTP/2 client codec + aggregator
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Client sends GET request
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/index");
        request.headers().add("host", "www.example.com");
        client.sendData(request).get();

        // Verify server received the request
        assertTrue("Server should receive data", serverRcv.size() >= 1);
        Object req = serverRcv.poll();
        assertTrue("Should be FullHttpRequest", req instanceof FullHttpRequest);
        FullHttpRequest decodedReq = (FullHttpRequest) req;
        assertEquals(HttpMethod.GET, decodedReq.method());
        assertEquals("/index", decodedReq.uri());

        // Server sends response
        ByteBuf respBody = toBody("Hello HTTP/2");
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, respBody);
        response.headers().set("content-type", "text/plain");
        server.sendData(response).get();

        // Verify client received the response
        assertTrue("Client should receive data", clientRcv.size() >= 1);
        Object resp = clientRcv.poll();
        assertTrue("Should be FullHttpResponse", resp instanceof FullHttpResponse);
        FullHttpResponse decodedResp = (FullHttpResponse) resp;
        assertEquals(200, decodedResp.status().code());
        assertEquals("Hello HTTP/2", readBody(decodedResp.content()));

        neta.shutdown();
    }

    // ========================= POST with Body =========================

    @Test
    public void testPostWithBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Client sends POST with JSON body
        ByteBuf reqBody = toBody("{\"name\":\"neta\"}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/api/data", reqBody);
        request.headers().add("host", "api.example.com");
        request.headers().add("content-type", "application/json");
        client.sendData(request).get();

        assertTrue("Server should receive request", serverRcv.size() >= 1);
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.POST, decodedReq.method());
        assertEquals("/api/data", decodedReq.uri());
        assertEquals("{\"name\":\"neta\"}", readBody(decodedReq.content()));

        // Server responds
        ByteBuf respBody = toBody("{\"status\":\"created\"}");
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.CREATED, respBody);
        response.headers().set("content-type", "application/json");
        server.sendData(response).get();

        assertTrue("Client should receive response", clientRcv.size() >= 1);
        FullHttpResponse decodedResp = (FullHttpResponse) clientRcv.poll();
        assertEquals(201, decodedResp.status().code());
        assertEquals("{\"status\":\"created\"}", readBody(decodedResp.content()));

        neta.shutdown();
    }

    // ========================= Multiple Methods =========================

    @Test
    public void testDeleteRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.DELETE, "/resource/42");
        request.headers().add("host", "api.example.com");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.DELETE, decoded.method());
        assertEquals("/resource/42", decoded.uri());

        neta.shutdown();
    }

    @Test
    public void testPatchRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        ByteBuf body = toBody("{\"field\":\"updated\"}");
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.PATCH, "/item/7", body);
        request.headers().add("host", "api.example.com");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decoded = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.PATCH, decoded.method());
        assertEquals("{\"field\":\"updated\"}", readBody(decoded.content()));

        neta.shutdown();
    }

    // ========================= Various Status Codes =========================

    @Test
    public void testStatusCode404() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Client sends request
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/missing");
        request.headers().add("host", "example.com");
        client.sendData(request).get();

        // Server responds 404
        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll(); // consume request
        ByteBuf body = toBody("Not Found");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NOT_FOUND, body);
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decoded = (FullHttpResponse) clientRcv.poll();
        assertEquals(404, decoded.status().code());
        assertEquals("Not Found", readBody(decoded.content()));

        neta.shutdown();
    }

    @Test
    public void testStatusCode500() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/error");
        request.headers().add("host", "example.com");
        client.sendData(request).get();

        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll();
        ByteBuf body = toBody("Internal Server Error");
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.INTERNAL_SERVER_ERROR, body);
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
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        // Send request with custom headers
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/custom");
        req.headers().add("host", "example.com");
        req.headers().add("x-request-id", "req-12345");
        req.headers().add("accept", "application/json");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals("req-12345", decodedReq.headers().get("x-request-id"));
        assertEquals("application/json", decodedReq.headers().get("accept"));

        // Send response with custom headers
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        resp.headers().set("x-response-id", "resp-67890");
        resp.headers().set("cache-control", "no-cache");
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decodedResp = (FullHttpResponse) clientRcv.poll();
        assertEquals("resp-67890", decodedResp.headers().get("x-response-id"));
        assertEquals("no-cache", decodedResp.headers().get("cache-control"));

        neta.shutdown();
    }

    // ========================= Large Body =========================

    @Test
    public void testLargeBodyRoundTrip() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));

        // 8KB body
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": payload data for HTTP/2 large body test.\n");
        }
        String largeBody = sb.toString();

        ByteBuf body = toBody(largeBody);
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, "/upload", body);
        req.headers().add("host", "upload.example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.POST, decodedReq.method());
        String receivedBody = readBody(decodedReq.content());
        assertEquals(largeBody, receivedBody);

        neta.shutdown();
    }

    // ========================= No Body Response =========================

    @Test
    public void testNoBodyResponse204() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
            ctx.addLast("h2", new Http2ServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            ctx.addLast("h2", new Http2ClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        Queue<Object> clientRcv = new ArrayDeque<>();
        server.subscribe(PlayLoad::isInbound, d -> serverRcv.offer(d.getData()));
        client.subscribe(PlayLoad::isInbound, d -> clientRcv.offer(d.getData()));

        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.DELETE, "/item/10");
        req.headers().add("host", "example.com");
        client.sendData(req).get();

        assertTrue(serverRcv.size() >= 1);
        serverRcv.poll();
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.NO_CONTENT);
        server.sendData(resp).get();

        assertTrue(clientRcv.size() >= 1);
        FullHttpResponse decoded = (FullHttpResponse) clientRcv.poll();
        assertEquals(204, decoded.status().code());

        neta.shutdown();
    }
}
