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
package net.hasor.neta.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.DefaultFullHttpRequest;
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import net.hasor.neta.codec.http.websocket.DefaultWebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketOpcode;
import net.hasor.neta.codec.http2.Http2Context;
import net.hasor.neta.codec.http3.Http3Context;
import net.hasor.neta.codec.quic.QuicContext;
import net.hasor.neta.codec.spdy.SpdyContext;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link HttpCodec} entry point class.
 * Verifies that each factory method produces a working protocol pipeline.
 */
public class HttpCodecTest {

    // ========================= Helpers =========================

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static String readByteBuf(ByteBuf buf) {
        return buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII);
    }

    private static ProtoContext mockContextWithStore() {
        Map<Class<?>, Object> contextMap = new ConcurrentHashMap<>();
        return (ProtoContext) java.lang.reflect.Proxy.newProxyInstance(ProtoContext.class.getClassLoader(), new Class[] { ProtoContext.class }, (proxy, method, args) -> {
            if ("byteBufAllocator".equals(method.getName())) {
                return ByteBufAllocator.DEFAULT;
            }
            if ("context".equals(method.getName())) {
                if (args.length == 1) {
                    return contextMap.get(args[0]);
                } else if (args.length == 2) {
                    if (args[1] != null) {
                        contextMap.put((Class<?>) args[0], args[1]);
                    }
                    return args[1];
                }
            }
            return null;
        });
    }

    // ========================= HTTP/1.1 Server Tests =========================

    @Test
    public void httpServer_decodeGetRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpServer(), VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String request = "GET /index.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n";
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.GET, decoded.method());
        assertEquals("/index.html", decoded.uri());
        assertEquals("www.example.com", decoded.headers().get("host"));

        neta.shutdown();
    }

    @Test
    public void httpServer_decodePostRequestWithBody() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpServer(), VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        String body = "{\"key\":\"value\"}";
        String request = "POST /api HTTP/1.1\r\nHost: localhost\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        client.sendData(toByteBuf(request)).get();

        assertEquals(1, rcvData.size());
        FullHttpRequest decoded = (FullHttpRequest) rcvData.poll();
        assertEquals(HttpMethod.POST, decoded.method());
        assertEquals("/api", decoded.uri());
        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    @Test
    public void httpServer_encodeResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel serverRaw = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), ctx -> {
        }, VrtSoConfig.asServer());
        VrtChannel serverCodec = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), HttpCodec.httpServer(), VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(serverCodec, serverRaw, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        serverRaw.subscribe(d -> rcvData.offer(d.getData()));

        String body = "Hello, World!";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Type", "text/plain");
        response.headers().add("Content-Length", String.valueOf(body.length()));
        serverCodec.sendData(response).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("Should contain status line", encoded.contains("HTTP/1.1 200 OK\r\n"));
        assertTrue("Should contain body", encoded.contains(body));

        neta.shutdown();
    }

    @Test
    public void httpServer_customMaxContentLength() throws Throwable {
        // With a very small max content length, we verify the pipeline is correctly assembled
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpServer(64), VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Small request within limit
        String request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n";
        client.sendData(toByteBuf(request)).get();
        assertEquals(1, rcvData.size());

        neta.shutdown();
    }

    // ========================= HTTP/1.1 Client Tests =========================

    @Test
    public void httpClient_decodeResponse() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel clientCodec = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpClient(), VrtSoConfig.asClient());
        VrtChannel serverRaw = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(serverRaw, clientCodec, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        clientCodec.subscribe(d -> rcvData.offer(d.getData()));

        String body = "{\"result\":42}";
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json\r\n" + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        serverRaw.sendData(toByteBuf(response)).get();

        assertEquals(1, rcvData.size());
        FullHttpResponse decoded = (FullHttpResponse) rcvData.poll();
        assertEquals(HttpStatus.OK, decoded.status());
        ByteBuf content = decoded.content();
        assertEquals(body, content.readString(content.readableBytes(), StandardCharsets.US_ASCII));

        neta.shutdown();
    }

    @Test
    public void httpClient_encodeRequest() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel clientCodec = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpClient(), VrtSoConfig.asClient());
        VrtChannel serverRaw = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(clientCodec, serverRaw, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        serverRaw.subscribe(d -> rcvData.offer(d.getData()));

        String body = "hello";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api", bodyBuf);
        request.headers().add("Host", "localhost");
        request.headers().add("Content-Length", String.valueOf(body.length()));
        clientCodec.sendData(request).get();

        assertTrue(rcvData.size() >= 1);
        StringBuilder result = new StringBuilder();
        Object msg;
        while ((msg = rcvData.poll()) != null) {
            result.append(readByteBuf((ByteBuf) msg));
        }
        String encoded = result.toString();
        assertTrue("Should contain request line", encoded.contains("POST /api HTTP/1.1\r\n"));
        assertTrue("Should contain body", encoded.contains(body));

        neta.shutdown();
    }

    // ========================= HTTP/1.1 Bidirectional Test =========================

    @Test
    public void http_bidirectional_serverAndClient() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.httpServer(), VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), HttpCodec.httpClient(), VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> serverRcv = new ArrayDeque<>();
        server.subscribe(d -> serverRcv.offer(d.getData()));
        Queue<Object> clientRcv = new ArrayDeque<>();
        client.subscribe(d -> clientRcv.offer(d.getData()));

        // Client sends request
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello");
        request.headers().add("Host", "localhost");
        client.sendData(request).get();

        assertEquals(1, serverRcv.size());
        FullHttpRequest decodedReq = (FullHttpRequest) serverRcv.poll();
        assertEquals(HttpMethod.GET, decodedReq.method());
        assertEquals("/hello", decodedReq.uri());

        // Server sends response
        String body = "OK";
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(body.length());
        bodyBuf.writeString(body, StandardCharsets.US_ASCII);
        bodyBuf.markWriter();
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, bodyBuf);
        response.headers().add("Content-Length", String.valueOf(body.length()));
        server.sendData(response).get();

        assertTrue("Client should receive at least one message", clientRcv.size() >= 1);
        FullHttpResponse decodedResp = null;
        for (Object item : clientRcv) {
            if (item instanceof FullHttpResponse) {
                decodedResp = (FullHttpResponse) item;
                break;
            }
        }
        assertNotNull("Should contain a FullHttpResponse", decodedResp);
        assertEquals(HttpStatus.OK, decodedResp.status());

        neta.shutdown();
    }

    // ========================= HTTP/2 Context Registration Tests =========================

    @Test
    public void http2Server_registersContext() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        // Use the initializer to configure context, then verify context registration
        net.hasor.neta.codec.http2.Http2ServerDuplexe duplexe = new net.hasor.neta.codec.http2.Http2ServerDuplexe();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertNotNull("Http2Context should be registered", h2ctx);
        assertTrue("Should be server mode", h2ctx.isServer());
        assertFalse("Should not be client mode", h2ctx.isClient());
    }

    @Test
    public void http2Client_registersContext() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        net.hasor.neta.codec.http2.Http2ClientDuplexe duplexe = new net.hasor.neta.codec.http2.Http2ClientDuplexe();
        duplexe.onInit(ctx);

        Http2Context h2ctx = ctx.context(Http2Context.class);
        assertNotNull("Http2Context should be registered", h2ctx);
        assertFalse("Should not be server mode", h2ctx.isServer());
        assertTrue("Should be client mode", h2ctx.isClient());
    }

    @Test
    public void http2Server_pipelineCreatedByHttpCodec() throws Throwable {
        // Verify HttpCodec.http2Server() creates a valid initializer
        assertNotNull("http2Server should return non-null", HttpCodec.http2Server());
    }

    @Test
    public void http2Client_pipelineCreatedByHttpCodec() throws Throwable {
        assertNotNull("http2Client should return non-null", HttpCodec.http2Client());
    }

    // ========================= HTTP/3 Context Registration Tests =========================

    @Test
    public void http3Server_registersContexts() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        net.hasor.neta.codec.http3.Http3ServerDuplexe duplexe = new net.hasor.neta.codec.http3.Http3ServerDuplexe();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertNotNull("Http3Context should be registered", h3ctx);
        assertTrue("Should be server mode", h3ctx.isServer());

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertNotNull("QuicContext should also be registered", quicCtx);
        assertTrue("QUIC should be server mode", quicCtx.isServer());
    }

    @Test
    public void http3Client_registersContexts() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        net.hasor.neta.codec.http3.Http3ClientDuplexe duplexe = new net.hasor.neta.codec.http3.Http3ClientDuplexe();
        duplexe.onInit(ctx);

        Http3Context h3ctx = ctx.context(Http3Context.class);
        assertNotNull("Http3Context should be registered", h3ctx);
        assertTrue("Should be client mode", h3ctx.isClient());

        QuicContext quicCtx = ctx.context(QuicContext.class);
        assertNotNull("QuicContext should also be registered", quicCtx);
        assertTrue("QUIC should be client mode", quicCtx.isClient());
    }

    // ========================= SPDY Context Registration Tests =========================

    @Test
    public void spdyServer_registersContext() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        net.hasor.neta.codec.spdy.SpdyServerDuplexe duplexe = new net.hasor.neta.codec.spdy.SpdyServerDuplexe();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertNotNull("SpdyContext should be registered", spdyCtx);
        assertTrue("Should be server mode", spdyCtx.isServer());
        assertEquals("Version should be 3", 3, spdyCtx.version());
    }

    @Test
    public void spdyClient_registersContext() throws Throwable {
        ProtoContext ctx = mockContextWithStore();
        net.hasor.neta.codec.spdy.SpdyClientDuplexe duplexe = new net.hasor.neta.codec.spdy.SpdyClientDuplexe();
        duplexe.onInit(ctx);

        SpdyContext spdyCtx = ctx.context(SpdyContext.class);
        assertNotNull("SpdyContext should be registered", spdyCtx);
        assertTrue("Should be client mode", spdyCtx.isClient());
    }

    // ========================= WebSocket Tests =========================

    @Test
    public void websocketServer_encodeDecodeTextFrame() throws Throwable {
        NetManager neta = new NetManager();

        // Server: WebSocket frame codec
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.websocketServer(), VrtSoConfig.asServer());

        // Client: raw bytes
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        // Build a raw text frame: FIN=1, opcode=1, unmasked, payload="Hello"
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        ByteBuf rawFrame = buildUnmaskedFrame(0x01, true, payload);
        client.sendData(rawFrame).get();

        assertEquals("Should decode one frame", 1, rcvData.size());
        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertEquals(WebSocketOpcode.TEXT, frame.opcode());
        assertTrue(frame.isFinalFragment());
        ByteBuf content = frame.content();
        assertEquals("Hello", content.readString(content.readableBytes(), StandardCharsets.UTF_8));

        neta.shutdown();
    }

    @Test
    public void websocketServer_encodeSendFrame() throws Throwable {
        NetManager neta = new NetManager();

        // Encoder side: WebSocket codec
        VrtChannel encoder = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.websocketServer(), VrtSoConfig.asServer());

        // Sink: raw bytes
        VrtChannel sink = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asClient());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(encoder, sink, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        sink.subscribe(d -> rcvData.offer(d.getData()));

        encoder.sendData(DefaultWebSocketFrame.text("World")).get();

        assertTrue("Should produce at least one ByteBuf", rcvData.size() >= 1);
        ByteBuf encoded = (ByteBuf) rcvData.poll();
        assertTrue("Encoded frame should have data", encoded.readableBytes() > 0);

        // Parse the first two bytes: FIN=1, opcode=1 (text)
        byte b0 = encoded.getByte(encoded.readerIndex());
        assertTrue("FIN bit should be set", (b0 & 0x80) != 0);
        assertEquals("Opcode should be 1 (TEXT)", 0x01, b0 & 0x0F);

        neta.shutdown();
    }

    @Test
    public void websocketClient_pipeline() throws Throwable {
        // Verify client pipeline is created and functional
        NetManager neta = new NetManager();

        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), HttpCodec.websocketClient(), VrtSoConfig.asClient());
        VrtChannel sink = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
        }, VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, sink, VrtTransfer.duplicate());

        Queue<Object> rcvData = new ArrayDeque<>();
        sink.subscribe(d -> rcvData.offer(d.getData()));

        client.sendData(DefaultWebSocketFrame.text("Ping")).get();
        assertTrue("Should encode frame", rcvData.size() >= 1);

        neta.shutdown();
    }

    // ========================= Factory Method Non-null Tests =========================

    @Test
    public void allFactoryMethods_returnNonNull() {
        assertNotNull(HttpCodec.httpServer());
        assertNotNull(HttpCodec.httpServer(512));
        assertNotNull(HttpCodec.httpClient());
        assertNotNull(HttpCodec.httpClient(512));
        assertNotNull(HttpCodec.http2Server());
        assertNotNull(HttpCodec.http2Client());
        assertNotNull(HttpCodec.http3Server());
        assertNotNull(HttpCodec.http3Client());
        assertNotNull(HttpCodec.spdyServer());
        assertNotNull(HttpCodec.spdyClient());
        assertNotNull(HttpCodec.websocketServer());
        assertNotNull(HttpCodec.websocketClient());
    }

    @Test
    public void defaultMaxContentLength_isOneMB() {
        assertEquals(1048576, HttpCodec.DEFAULT_MAX_CONTENT_LENGTH);
    }

    // ========================= WebSocket Frame Builder Helper =========================

    private static ByteBuf buildUnmaskedFrame(int opcode, boolean fin, byte[] payload) {
        int headerSize = 2;
        if (payload.length >= 126 && payload.length <= 65535) {
            headerSize += 2;
        }
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(headerSize + payload.length);
        buf.writeByte((byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F)));
        int lenByte = payload.length < 126 ? payload.length : 126;
        buf.writeByte((byte) lenByte);
        if (lenByte == 126) {
            buf.writeByte((byte) ((payload.length >> 8) & 0xFF));
            buf.writeByte((byte) (payload.length & 0xFF));
        }
        buf.writeBytes(payload, 0, payload.length);
        buf.markWriter();
        return buf;
    }
}
