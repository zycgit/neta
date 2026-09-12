/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.*;
import net.hasor.neta.codec.http.routing.H2CUpgradeServerDuplex;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;

public class WebSocketHttp2StandardRfc8441Test extends AbstractHttpTest {
    private static final int    MAX_CONTENT_LENGTH = 1024 * 1024;
    private static final String ENCODED_SETTINGS   = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 });
    private static final String WS_PREFIX          = "ws:";
    private static final String HTTP_PREFIX        = "http:";

    private static class H2cPipePair {
        private final VirtualPipe serverTransport;
        private final VirtualPipe clientProtocol;

        private H2cPipePair(VirtualPipe serverTransport, VirtualPipe clientProtocol) {
            this.serverTransport = serverTransport;
            this.clientProtocol = clientProtocol;
        }
    }

    @Test
    public void testOneHttp2ConnectionCanCarryMultipleStandardWebSocketStreams() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openStandardRfc8441Pipe(neta);

            openWebSocketStream(pipe, 3, "/ws-alpha");
            openWebSocketStream(pipe, 5, "/ws-beta");

            sendWebSocketTextAndExpectEcho(pipe, 3, "alpha");
            sendWebSocketTextAndExpectEcho(pipe, 5, "beta");

            pipe.client().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(3, Http2ResetEvent.CANCEL));
            assertTrue(pipeState(pipe), waitUntil(() -> hasResetEvent(pipe, 3), 1000L));

            assertTrue(hasHandshakeEvent(pipe.serverEvents(), 3));
            assertTrue(hasHandshakeEvent(pipe.serverEvents(), 5));
            assertNull(findResetEvent(pipe.clientEvents(), 5));
            assertNull(findResetEvent(pipe.serverEvents(), 5));
            assertFalse(pipe.server().isClose());

            sendWebSocketTextAndExpectEcho(pipe, 5, "beta-after-reset");
        });
    }

    @Test
    public void testStandardHttp2WebSocketCanCommunicateAfterHandshake() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openStandardRfc8441Pipe(neta);

            openWebSocketStream(pipe, 3, "/ws-chat");
            sendWebSocketTextAndExpectEcho(pipe, 3, "hello-h2");
            assertTrue(hasHandshakeEvent(pipe.clientEvents(), 3));
            assertTrue(hasHandshakeEvent(pipe.serverEvents(), 3));
        });
    }

    @Test
    public void testHttpRequestStillWorksWhileStandardWebSocketStreamsRemainAlive() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openStandardRfc8441Pipe(neta);

            openWebSocketStream(pipe, 3, "/ws-alpha");
            openWebSocketStream(pipe, 5, "/ws-beta");
            sendWebSocketTextAndExpectEcho(pipe, 3, "alpha-before-http");

            FullHttpRequest httpRequest = postRequest("/http-echo", "body");
            httpRequest.streamId(7);
            pipe.client().sendData(httpRequest).get();

            assertTrue(pipeState(pipe), waitUntil(() -> findHttpResponse(pipe.clientInbound(), 7) != null, 1000L));
            assertNoPipeErrors(pipe);

            List<HttpObject> inbound = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                HttpResponse response = findHttpResponse(inbound, 7);
                assertNotNull(response);
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(HTTP_PREFIX + "/http-echo:body", decodeHttpBody(inbound, 7));
                assertTrue(hasHandshakeEvent(pipe.serverEvents(), 3));
                assertTrue(hasHandshakeEvent(pipe.serverEvents(), 5));
            } finally {
                free(inbound);
            }

            sendWebSocketTextAndExpectEcho(pipe, 5, "beta-after-http");
        });
    }

    @Test
    public void testMultipleConnectionsCanIndependentlyOpenStandardWebSockets() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe first = openStandardRfc8441Pipe(neta);
            VirtualPipe second = openStandardRfc8441Pipe(neta);

            openWebSocketStream(first, 3, "/ws-first");
            openWebSocketStream(second, 3, "/ws-second");

            first.client().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(3, Http2ResetEvent.CANCEL));
            assertTrue(pipeState(first), waitUntil(() -> hasResetEvent(first, 3), 1000L));
            assertNoPipeErrors(second);

            assertTrue(hasHandshakeEvent(first.serverEvents(), 3));
            assertTrue(hasHandshakeEvent(second.serverEvents(), 3));
            assertNull(findResetEvent(second.clientEvents(), 3));
            assertNull(findResetEvent(second.serverEvents(), 3));
            assertFalse(second.server().isClose());
        });
    }

    @Test
    public void testH2cUpgradeThenCanCarryStandardWebSocketHandshake() throws Throwable {
        autoCloseNeta(neta -> {
            H2cPipePair pipe = openStandardRfc8441OverH2cPipe(neta);
            establishH2cUpgrade(pipe, "/h2c-bootstrap");

            openWebSocketStream(pipe, 3, "/ws-h2c-standard");
            sendWebSocketTextAndExpectEcho(pipe, 3, "hello-h2c");
            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 3));
            assertTrue(hasHandshakeEvent(pipe.clientProtocol.channelEvents(), 3));
            assertNull(findResetEvent(pipe.clientProtocol.channelEvents(), 3));
            assertNoPipeErrors(pipe);
        });
    }

    @Test
    public void testHttp1ThenH2cUpgradeThenStandardWebSocketAndHttp2Traffic() throws Throwable {
        autoCloseNeta(neta -> {
            H2cPipePair pipe = openStandardRfc8441OverH2cPipe(neta);

            sendHttp1RequestAndExpectResponse(pipe, "/before-upgrade", "h1-body");
            establishH2cUpgrade(pipe, "/h2c-bootstrap");

            openWebSocketStream(pipe, 3, "/ws-after-upgrade");
            sendWebSocketTextAndExpectEcho(pipe, 3, "after-upgrade");
            sendHttp2RequestAndExpectResponse(pipe, 5, "/http2-after-ws", "h2-body");

            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 3));
            assertTrue(hasHandshakeEvent(pipe.clientProtocol.channelEvents(), 3));
        });
    }

    private VirtualPipe openStandardRfc8441Pipe(NetManager neta) throws Throwable {
        return openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                    .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        pb.policy(policy).byDefault(partitionCtx -> {
                            partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                        }).byInitializer(partitionCtx -> {
                            final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
                            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                                branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplex(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                            }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                                branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                                branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
                            });
                            routingControl[0] = routing.control();
                            partitionCtx.addLast("client-route", routing.build());
                        });
                    }).build().config(clientCtx);
        }, serverCtx -> {
            ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(true)).nextDuplex("h2-message", new Http2ObjectDuplex(true)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                pb.policy(policy).byDefault(partitionCtx -> {
                    partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                }).byInitializer(partitionCtx -> {
                    final ProtoRoutingControl[] streamRoutingControl = new ProtoRoutingControl[1];
                    ProtoRoutingBuilder<Object, Object> streamRouting = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                        branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplex(streamRoutingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                        branchCtx.addLastDecoder("http-request", new HttpRequestAggregator(MAX_CONTENT_LENGTH));
                        branchCtx.addLastDecoder("http-handler", httpEchoHandler());
                    }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                        branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                        branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
                        branchCtx.addLastDecoder("ws-handler", webSocketEchoHandler());
                    });
                    streamRoutingControl[0] = streamRouting.control();
                    partitionCtx.addLast("server-route", streamRouting.build());
                });
            }).build().config(serverCtx);
        });
    }

    private H2cPipePair openStandardRfc8441OverH2cPipe(NetManager neta) throws Throwable {
        VirtualPipe serverTransport = openVirtualPipe(neta, serverCtx -> {
            ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                ProtoRoutingControl routingControl = routing.control();

                routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                        .nextDuplex("http-codec", new HttpServerDuplex())//
                        .nextDuplex("h1-upgrade", new H2CUpgradeServerDuplex(routingControl))//
                        .nextDecoder("http-request", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                        .nextDecoder("http-handler", httpEchoHandler()))//
                        .branch(HttpRouteKey.BRANCH_H2C, b -> b//
                                .nextDuplex("http-codec", new HttpServerDuplex())//
                                .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplex(routingControl)))//
                        .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                .nextDuplex("h2-frame", new Http2FrameDuplex(true))//
                                .nextDuplex("h2-message", new Http2ObjectDuplex(true, routingControl))//
                                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                    pb.policy(policy).byDefault(partitionCtx -> {
                                        partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                                    }).byInitializer(partitionCtx -> {
                                        final ProtoRoutingControl[] streamRoutingControl = new ProtoRoutingControl[1];
                                        ProtoRoutingBuilder<Object, Object> streamRouting = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                                            branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplex(streamRoutingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                                            branchCtx.addLastDecoder("http-request", new HttpRequestAggregator(MAX_CONTENT_LENGTH));
                                            branchCtx.addLastDecoder("http-handler", httpEchoHandler());
                                        }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                                            branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                                            branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
                                            branchCtx.addLastDecoder("ws-handler", webSocketEchoHandler());
                                        });
                                        streamRoutingControl[0] = streamRouting.control();
                                        partitionCtx.addLast("server-route", streamRouting.build());
                                    });
                                }));
            }).config(serverCtx);
        }, VrtSoConfig.asServer());

        VirtualPipe clientProtocol = openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                    .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        pb.policy(policy).byDefault(partitionCtx -> {
                            partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                        }).byInitializer(partitionCtx -> {
                            final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
                            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                                branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplex(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                            }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                                branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                                branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
                            });
                            routingControl[0] = routing.control();
                            partitionCtx.addLast("client-route", routing.build());
                        });
                    }).build().config(clientCtx);
        }, VrtSoConfig.asClient());

        return new H2cPipePair(serverTransport, clientProtocol);
    }

    private ProtoHandler<HttpObject, Object> httpEchoHandler() {
        return new ProtoHandler<HttpObject, Object>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (!(item instanceof FullHttpRequest)) {
                        continue;
                    }

                    FullHttpRequest request = (FullHttpRequest) item;
                    try {
                        byte[] data = (HTTP_PREFIX + request.uri() + ":" + utf8(request.content().retain())).getBytes(StandardCharsets.UTF_8);
                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpStatus.OK, ByteBuf.wrap(data));
                        response.streamId(request.streamId());
                        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
                        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
                        context.sendData(response).get();
                    } finally {
                        request.release();
                    }
                }
                return ProtoStatus.Next;
            }
        };
    }

    private ProtoHandler<WebSocketMessage, Object> webSocketEchoHandler() {
        return new ProtoHandler<WebSocketMessage, Object>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketMessage> src, ProtoSndQueue<Object> dst) throws Throwable {
                while (src.hasMore()) {
                    WebSocketMessage item = src.takeMessage();
                    if (item == null) {
                        continue;
                    }

                    try {
                        if (item instanceof TextWebSocketMessage) {
                            context.sendData(WebSocketUtils.textMessage(ascii(WS_PREFIX + text(item.content().copy()))).streamId(item.streamId())).get();
                        }
                    } finally {
                        item.release();
                    }
                }
                return ProtoStatus.Next;
            }
        };
    }

    private void openWebSocketStream(VirtualPipe pipe, int streamId, String path) throws Throwable {
        FullHttpRequest request = WebSocketUtils.createHttp2Handshake(WebSocketVersion.V13, "ws://example.com" + path);
        request.streamId(streamId);
        pipe.client().sendData(request).get();
        assertTrue(pipeState(pipe), waitUntil(() -> hasHandshakeEvent(pipe.serverEvents(), streamId) && hasHandshakeEvent(pipe.clientEvents(), streamId), 1000L));
        assertNoPipeErrors(pipe);
    }

    private void openWebSocketStream(H2cPipePair pipe, int streamId, String path) throws Throwable {
        FullHttpRequest request = WebSocketUtils.createHttp2Handshake(WebSocketVersion.V13, "ws://example.com" + path);
        request.streamId(streamId);
        pipe.clientProtocol.channel().sendData(request).get();
        pumpExchange(pipe);
        assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> hasHandshakeEvent(pipe.serverTransport.channelEvents(), streamId) && hasHandshakeEvent(pipe.clientProtocol.channelEvents(), streamId), 1000L));
        assertNoPipeErrors(pipe);
    }

    private void sendWebSocketTextAndExpectEcho(VirtualPipe pipe, int streamId, String payload) throws Throwable {
        pipe.client().sendData(WebSocketUtils.textMessage(ascii(payload)).streamId(streamId)).get();
        assertTrue(pipeState(pipe), waitUntil(() -> findTextWebSocketMessage(pipe.clientInbound(), streamId) != null, 1000L));

        List<HttpObject> inbound = castHttpObjects(drainQueue(pipe.clientInbound()));
        try {
            assertEquals(WS_PREFIX + payload, findTextWebSocketMessage(inbound, streamId));
        } finally {
            free(inbound);
        }
        assertNoPipeErrors(pipe);
    }

    private void sendWebSocketTextAndExpectEcho(H2cPipePair pipe, int streamId, String payload) throws Throwable {
        pipe.clientProtocol.channel().sendData(WebSocketUtils.textMessage(ascii(payload)).streamId(streamId)).get();
        pumpExchange(pipe);
        assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> findTextWebSocketMessage(pipe.clientProtocol.channelInbound(), streamId) != null, 1000L));

        List<HttpObject> inbound = castHttpObjects(drainQueue(pipe.clientProtocol.channelInbound()));
        try {
            assertEquals(WS_PREFIX + payload, findTextWebSocketMessage(inbound, streamId));
        } finally {
            free(inbound);
        }
        assertNoPipeErrors(pipe);
    }

    private void sendHttp1RequestAndExpectResponse(H2cPipePair pipe, String path, String body) throws Throwable {
        byte[] requestBytes = ("POST " + path + " HTTP/1.1\r\n" + "Host: example.com\r\n" + "Content-Length: " + body.getBytes(StandardCharsets.US_ASCII).length + "\r\n" + "\r\n" + body).getBytes(StandardCharsets.US_ASCII);
        pipe.serverTransport.channel().receiveData(ByteBuf.wrap(requestBytes));
        assertTrue(pipeState(pipe), waitUntil(() -> !pipe.serverTransport.channelOutbound().isEmpty(), 1000L));

        String responseText = new String(drainRawBytes(pipe.serverTransport.channelOutbound()), StandardCharsets.US_ASCII);
        assertTrue("responseText=" + responseText + ", " + pipeState(pipe), responseText.startsWith("HTTP/1.1 200"));
        assertTrue("responseText=" + responseText + ", " + pipeState(pipe), responseText.contains(HTTP_PREFIX + path + ":" + body));
        assertTrue("serverTransportInboundErrors=" + pipe.serverTransport.channelInboundErrors(), pipe.serverTransport.channelInboundErrors().isEmpty());
        assertTrue("serverTransportOutboundErrors=" + pipe.serverTransport.channelOutboundErrors(), pipe.serverTransport.channelOutboundErrors().isEmpty());
    }

    private void sendHttp2RequestAndExpectResponse(H2cPipePair pipe, int streamId, String path, String body) throws Throwable {
        FullHttpRequest request = postRequest(path, body);
        request.streamId(streamId);
        pipe.clientProtocol.channel().sendData(request).get();
        pumpExchange(pipe);
        assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> findHttpResponse(pipe.clientProtocol.channelInbound(), streamId) != null, 1000L));

        List<HttpObject> inbound = castHttpObjects(drainQueue(pipe.clientProtocol.channelInbound()));
        try {
            HttpResponse response = findHttpResponse(inbound, streamId);
            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.status());
            assertEquals(HTTP_PREFIX + path + ":" + body, decodeHttpBody(inbound, streamId));
        } finally {
            free(inbound);
        }
        assertNoPipeErrors(pipe);
    }

    private void establishH2cUpgrade(H2cPipePair pipe, String path) throws Throwable {
        pipe.serverTransport.channel().receiveData(ascii("GET " + path + " HTTP/1.1\r\n" + "Host: example.com\r\n" + "Connection: Upgrade, HTTP2-Settings\r\n" + "Upgrade: h2c\r\n" + "HTTP2-Settings: " + ENCODED_SETTINGS + "\r\n" + "\r\n"));
        assertTrue(pipeState(pipe), waitUntil(() -> !pipe.serverTransport.channelOutbound().isEmpty() || !pipe.serverTransport.channelInboundErrors().isEmpty() || !pipe.serverTransport.channelOutboundErrors().isEmpty(), 1000L));

        byte[] firstOutbound = drainRawBytes(pipe.serverTransport.channelOutbound());
        int headerEnd = findHeaderEnd(firstOutbound);
        assertTrue(headerEnd > 0);

        String responseHead = new String(firstOutbound, 0, headerEnd, StandardCharsets.US_ASCII);
        assertTrue(responseHead.startsWith("HTTP/1.1 101"));
        assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

        byte[] h2Bytes = Arrays.copyOfRange(firstOutbound, headerEnd, firstOutbound.length);
        if (h2Bytes.length > 0) {
            pipe.clientProtocol.channel().receiveData(ByteBuf.wrap(h2Bytes));
        }

        pumpExchange(pipe);
        free(castHttpObjects(drainQueue(pipe.clientProtocol.channelInbound())));
        drainQueue(pipe.clientProtocol.channelEvents());
        assertNoPipeErrors(pipe);
    }

    private void pumpExchange(H2cPipePair pipe) throws Throwable {
        boolean progressed;
        do {
            progressed = false;

            byte[] clientOutbound = drainRawBytes(pipe.clientProtocol.channelOutbound());
            if (clientOutbound.length > 0) {
                pipe.serverTransport.channel().receiveData(ByteBuf.wrap(clientOutbound));
                progressed = true;
            }

            byte[] serverOutbound = drainRawBytes(pipe.serverTransport.channelOutbound());
            if (serverOutbound.length > 0) {
                pipe.clientProtocol.channel().receiveData(ByteBuf.wrap(serverOutbound));
                progressed = true;
            }
        } while (progressed);
    }

    private boolean waitUntilEx(H2cPipePair pipe, BooleanSupplier probe, long timeoutMs) throws Throwable {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            pumpExchange(pipe);
            if (probe.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        pumpExchange(pipe);
        return probe.getAsBoolean();
    }

    private byte[] drainRawBytes(Queue<Object> queue) {
        List<ByteBuf> payload = new java.util.ArrayList<ByteBuf>();
        for (Object item : drainQueue(queue)) {
            if (item instanceof ByteBuf) {
                payload.add((ByteBuf) item);
            }
        }
        return payload.isEmpty() ? new byte[0] : bytes(payload.toArray(new ByteBuf[0]));
    }

    private static int findHeaderEnd(byte[] rawBytes) {
        for (int i = 0; i <= rawBytes.length - 4; i++) {
            if (rawBytes[i] == '\r' && rawBytes[i + 1] == '\n' && rawBytes[i + 2] == '\r' && rawBytes[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static boolean hasResetEvent(VirtualPipe pipe, int streamId) {
        return findResetEvent(pipe.clientEvents(), streamId) != null || findResetEvent(pipe.serverEvents(), streamId) != null;
    }

    private static boolean hasHandshakeEvent(Iterable<SoEvent> events, int streamId) {
        for (SoEvent event : events) {
            if (event != null && event.getData() instanceof WebSocketHandshakeEvent) {
                WebSocketHandshakeEvent handshakeEvent = (WebSocketHandshakeEvent) event.getData();
                if (handshakeEvent.streamId() == streamId) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Http2ResetEvent findResetEvent(Iterable<SoEvent> events, int streamId) {
        for (SoEvent event : events) {
            if (event != null && event.getData() instanceof Http2ResetEvent) {
                Http2ResetEvent resetEvent = (Http2ResetEvent) event.getData();
                if (resetEvent.streamId() == streamId) {
                    return resetEvent;
                }
            }
        }
        return null;
    }

    private static HttpResponse findHttpResponse(Iterable<?> items, int streamId) {
        for (Object item : items) {
            if (item instanceof HttpResponse && ((HttpObject) item).streamId() == streamId) {
                return (HttpResponse) item;
            }
        }
        return null;
    }

    private static String findTextWebSocketMessage(Iterable<?> items, int streamId) {
        for (Object item : items) {
            if (item instanceof TextWebSocketMessage && ((TextWebSocketMessage) item).streamId() == streamId) {
                return text(((TextWebSocketMessage) item).content().copy());
            }
        }
        return null;
    }

    private static String decodeHttpBody(List<HttpObject> inbound, int streamId) {
        StringBuilder builder = new StringBuilder();
        for (HttpObject item : inbound) {
            if (item instanceof HttpContent && item.streamId() == streamId) {
                builder.append(utf8(((HttpContent) item).content().retain()));
            }
        }
        return builder.toString();
    }

    private void assertNoPipeErrors(VirtualPipe pipe) {
        assertTrue("clientInboundErrors=" + pipe.clientInboundErrors(), pipe.clientInboundErrors().isEmpty());
        assertTrue("clientOutboundErrors=" + pipe.clientOutboundErrors(), pipe.clientOutboundErrors().isEmpty());
        assertTrue("serverInboundErrors=" + pipe.serverInboundErrors(), pipe.serverInboundErrors().isEmpty());
        assertTrue("serverOutboundErrors=" + pipe.serverOutboundErrors(), pipe.serverOutboundErrors().isEmpty());
    }

    private void assertNoPipeErrors(H2cPipePair pipe) {
        assertTrue("serverTransportInboundErrors=" + pipe.serverTransport.channelInboundErrors(), pipe.serverTransport.channelInboundErrors().isEmpty());
        assertTrue("serverTransportOutboundErrors=" + pipe.serverTransport.channelOutboundErrors(), pipe.serverTransport.channelOutboundErrors().isEmpty());
        assertTrue("protocolInboundErrors=" + pipe.clientProtocol.channelInboundErrors(), pipe.clientProtocol.channelInboundErrors().isEmpty());
        assertTrue("protocolOutboundErrors=" + pipe.clientProtocol.channelOutboundErrors(), pipe.clientProtocol.channelOutboundErrors().isEmpty());
    }

    private String pipeState(VirtualPipe pipe) {
        return "clientInbound=" + pipe.clientInbound() + ", clientEvents=" + pipe.clientEvents() + ", serverInbound=" + pipe.serverInbound() + ", serverEvents=" + pipe.serverEvents() + ", clientInboundErrors=" + pipe.clientInboundErrors() + ", clientOutboundErrors=" + pipe.clientOutboundErrors() + ", serverInboundErrors=" + pipe.serverInboundErrors() + ", serverOutboundErrors=" + pipe.serverOutboundErrors();
    }

    private String pipeState(H2cPipePair pipe) {
        return "serverTransportOutbound=" + pipe.serverTransport.channelOutbound() + ", serverTransportEvents=" + pipe.serverTransport.channelEvents() + ", protocolClientInbound=" + pipe.clientProtocol.channelInbound() + ", protocolClientEvents=" + pipe.clientProtocol.channelEvents() + ", serverTransportInboundErrors=" + pipe.serverTransport.channelInboundErrors() + ", serverTransportOutboundErrors=" + pipe.serverTransport.channelOutboundErrors() + ", protocolInboundErrors=" + pipe.clientProtocol.channelInboundErrors() + ", protocolOutboundErrors=" + pipe.clientProtocol.channelOutboundErrors();
    }
}
