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
package net.hasor.neta.codec.http.websocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.*;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketHttp2NonStandardRfc6455Test extends AbstractHttp2Test {
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
    public void testHttp2CanCarryNonStandardRfc6455Handshake() throws Throwable {
        autoCloseNeta(neta -> {
            H2cPipePair pipe = openNonStandardRfc6455OverH2cPipe(neta);
            establishH2cUpgrade(pipe, "/h2c-bootstrap");

            openWebSocketStream(pipe, 3, "/ws-alpha");
            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 3));
            assertNull(findResetEvent(pipe.clientProtocol.channelEvents(), 3));
            assertNoPipeErrors(pipe);
        });
    }

    @Test
    public void testClosingOneHttp2WebSocketStreamDoesNotBreakAnother() throws Throwable {
        autoCloseNeta(neta -> {
            H2cPipePair pipe = openNonStandardRfc6455OverH2cPipe(neta);
            establishH2cUpgrade(pipe, "/h2c-bootstrap");

            openWebSocketStream(pipe, 3, "/ws-alpha");
            openWebSocketStream(pipe, 5, "/ws-beta");
            assertNoPipeErrors(pipe);

            pipe.clientProtocol.channel().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(3, Http2ResetEvent.CANCEL));
            pumpExchange(pipe);
            assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> hasResetEvent(pipe, 3), 1000L));
            assertNull(findResetEvent(pipe.clientProtocol.channelEvents(), 5));
            assertNull(findResetEvent(pipe.serverTransport.channelEvents(), 5));
            assertFalse(pipe.serverTransport.channel().isClose());
            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 5));
        });
    }

    @Test
    public void testHttpRequestStillWorksWhileAnotherHttp2WebSocketStreamRemainsAlive() throws Throwable {
        autoCloseNeta(neta -> {
            H2cPipePair pipe = openNonStandardRfc6455OverH2cPipe(neta);
            establishH2cUpgrade(pipe, "/h2c-bootstrap");

            openWebSocketStream(pipe, 3, "/ws-alpha");
            openWebSocketStream(pipe, 5, "/ws-beta");

            FullHttpRequest httpRequest = postRequest("/http-echo", "body");
            httpRequest.streamId(7);
            pipe.clientProtocol.channel().sendData(httpRequest).get();
            pumpExchange(pipe);

            assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> findHttpResponse(pipe.clientProtocol.channelInbound(), 7) != null, 1000L));
            assertNoPipeErrors(pipe);

            List<HttpObject> inbound = castHttpObjects(drainQueue(pipe.clientProtocol.channelInbound()));
            try {
                HttpResponse response = findHttpResponse(inbound, 7);
                assertNotNull(response);
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(HTTP_PREFIX + "/http-echo:body", decodeHttpBody(inbound, 7));
            } finally {
                free(inbound);
            }

            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 3));
            assertTrue(hasHandshakeEvent(pipe.serverTransport.channelEvents(), 5));
        });
    }

    //

    private H2cPipePair openNonStandardRfc6455OverH2cPipe(net.hasor.neta.channel.NetManager neta) throws Throwable {
        VirtualPipe serverTransport = openVirtualPipe(neta, serverCtx -> {
            ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                ProtoRoutingControl routingControl = routing.control();

                routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                                .nextDuplex("http-codec", new HttpServerDuplexe())//
                                .nextDecoder("http-request", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                                .nextDecoder("http-handler", httpEchoHandler()))//
                        .branch(HttpRouteKey.BRANCH_H2C, b -> b//
                                .nextDuplex("http-codec", new HttpServerDuplexe())  //
                                .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl)))//
                        .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                                .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                    pb.policy(policy).byDefault(partitionCtx -> {
                                        partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                                    }).byInitializer(partitionCtx -> {
                                        final ProtoRoutingControl[] streamRoutingControl = new ProtoRoutingControl[1];
                                        ProtoRoutingBuilder<Object, Object> streamRouting = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                                            branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(streamRoutingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                                            branchCtx.addLastDecoder("http-request", new HttpRequestAggregator(MAX_CONTENT_LENGTH));
                                            branchCtx.addLastDecoder("http-handler", httpEchoHandler());
                                        }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                                            branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                                            branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                                            branchCtx.addLastDecoder("ws-handler", webSocketEchoHandler());
                                        });
                                        streamRoutingControl[0] = streamRouting.control();
                                        partitionCtx.addLast("server-route", streamRouting.build());
                                    });
                                }));
            }).config(serverCtx);
        }, VrtSoConfig.asServer());

        VirtualPipe clientProtocol = openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplexe(false)).nextDuplex("h2-message", new Http2ObjectDuplexe(false)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                pb.policy(policy).byDefault(partitionCtx -> {
                    partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                }).byInitializer(partitionCtx -> {
                    final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
                    ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                        branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplexer(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                    }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                        branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                    });
                    routingControl[0] = routing.control();
                    partitionCtx.addLast("client-route", routing.build());
                });
            }).config(clientCtx);
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
                        context.sendData(textResponse(request.streamId(), HTTP_PREFIX + request.uri() + ":" + utf8(request.content().retain()))).get();
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

    private FullHttpRequest webSocketHandshake(int streamId, String path) {
        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "ws://example.com" + path);
        request.streamId(streamId);
        return request;
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

    private void openWebSocketStream(H2cPipePair pipe, int streamId, String path) throws Throwable {
        pipe.clientProtocol.channel().sendData(webSocketHandshake(streamId, path)).get();
        pumpExchange(pipe);
        assertTrue(pipeState(pipe), waitUntilEx(pipe, () -> hasHandshakeEvent(pipe.serverTransport.channelEvents(), streamId), 1000L));
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

    private static boolean hasResetEvent(H2cPipePair pipe, int streamId) {
        return findResetEvent(pipe.clientProtocol.channelEvents(), streamId) != null || findResetEvent(pipe.serverTransport.channelEvents(), streamId) != null;
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

    private static String decodeHttpBody(List<HttpObject> inbound, int streamId) {
        StringBuilder builder = new StringBuilder();
        for (HttpObject item : inbound) {
            if (item instanceof HttpContent && item.streamId() == streamId) {
                builder.append(utf8(((HttpContent) item).content().retain()));
            }
        }
        return builder.toString();
    }

    private void assertNoPipeErrors(H2cPipePair pipe) {
        assertTrue(pipe.serverTransport.channelInboundErrors().isEmpty());
        assertTrue(pipe.serverTransport.channelOutboundErrors().isEmpty());
        assertTrue(pipe.clientProtocol.channelInboundErrors().isEmpty());
        assertTrue(pipe.clientProtocol.channelOutboundErrors().isEmpty());
    }

    private String pipeState(H2cPipePair pipe) {
        return "serverTransportOutbound=" + pipe.serverTransport.channelOutbound() + ", serverTransportEvents=" + pipe.serverTransport.channelEvents() + ", protocolClientInbound=" + pipe.clientProtocol.channelInbound() + ", protocolClientEvents=" + pipe.clientProtocol.channelEvents() + ", serverTransportInboundErrors=" + pipe.serverTransport.channelInboundErrors() + ", serverTransportOutboundErrors=" + pipe.serverTransport.channelOutboundErrors() + ", protocolInboundErrors=" + pipe.clientProtocol.channelInboundErrors() + ", protocolOutboundErrors=" + pipe.clientProtocol.channelOutboundErrors();
    }
}