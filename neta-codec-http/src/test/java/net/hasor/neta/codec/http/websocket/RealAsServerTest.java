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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpServerDuplexe;
import net.hasor.neta.codec.http.real.websocket.OkHttpWebSocketClientHarness;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RealAsServerTest extends AbstractWebSocketTest {
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String BRANCH_HTTP      = "http";
    private static final String BRANCH_WEBSOCKET = HttpRouteKey.BRANCH_SOCKET;

    private ProtoHandler<HttpObject, HttpObject> relayOutboundEvents() {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) throws Throwable {
                Object eventData = event.getData();
                if (eventData instanceof PingWebSocketEvent) {
                    context.fireUserEventSnd(PingWebSocketEvent.class, (PingWebSocketEvent) eventData);
                    return false;
                }
                if (eventData instanceof PongWebSocketEvent) {
                    context.fireUserEventSnd(PongWebSocketEvent.class, (PongWebSocketEvent) eventData);
                    return false;
                }
                return true;
            }
        };
    }

    private ThroughProtoHandler<WebSocketMessage> pongEventRecorder(CountDownLatch pongLatch, AtomicReference<String> pongPayload) {
        return new ThroughProtoHandler<WebSocketMessage>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getData() instanceof PongWebSocketEvent) {
                    PongWebSocketEvent pongEvent = (PongWebSocketEvent) event.getData();
                    pongPayload.set(pongEvent.content().readString(pongEvent.content().readableBytes(), StandardCharsets.US_ASCII));
                    pongLatch.countDown();
                    pongEvent.release();
                }
                return true;
            }
        };
    }

    private ProtoHandler<WebSocketMessage, Object> webSocketBusinessHandler() {
        return (context, src, dst) -> {
            while (src.hasMore()) {
                WebSocketMessage message = src.takeMessage();
                if (message == null) {
                    continue;
                }
                try {
                    if (message instanceof TextWebSocketMessage) {
                        String content = text(message.content().retain());
                        if ("trigger-ping".equals(content)) {
                            context.fireUserEventSnd(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-okhttp")));
                        } else {
                            context.sendData(WebSocketUtils.textMessage(ascii("[Echo] " + content))).get();
                        }
                    } else if (message instanceof BinaryWebSocketMessage) {
                        context.sendData(WebSocketUtils.binaryMessage(message.content().retain())).get();
                    }
                } finally {
                    message.release();
                }
            }
            return ProtoStatus.Next;
        };
    }

    @Test
    public void testNetaServerWithDynamicRoutingHandlesTextBinaryAndPingPong() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        OkHttpWebSocketClientHarness harness = null;
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    return BRANCH_WEBSOCKET;
                });
                routing.branch(BRANCH_HTTP, branch -> {
                    branch.nextDecoder("http-pass", (context, src, dst) -> {
                        while (src.hasMore()) {
                            dst.offerMessage(src.takeMessage());
                        }
                        return ProtoStatus.Next;
                    });
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    ProtoRoutingBuilder<Object, Object> webSocketRouting = ProtoHelper.typedRoutingAsStatic((innerContext, innerRcvUp, innerSndDown) -> BRANCH_HANDSHAKE);
                    webSocketRouting.branchByInitializer(BRANCH_HANDSHAKE, innerBranchCtx -> {
                        innerBranchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        innerBranchCtx.addLastDecoder("ws-route-switch", switchRouteOnHandshake(BRANCH_WEBSOCKET));
                    });
                    webSocketRouting.branchByInitializer(BRANCH_WEBSOCKET, innerBranchCtx -> {
                        innerBranchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        innerBranchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                        innerBranchCtx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                        innerBranchCtx.addLastDecoder("ws-echo", webSocketBusinessHandler());
                    });
                    branchCtx.addLast("ws-route", webSocketRouting.build());
                });
                ctx.addLast("ws-route", routing.build());
            }, SoConfig.TCP());
            harness = OkHttpWebSocketClientHarness.connect(port, "/chat");
            harness.awaitOpen();
            harness.sendText("hello-neta");
            assertEquals("[Echo] hello-neta", harness.awaitText());
            harness.sendBinary("ABCD");
            assertEquals("ABCD", harness.awaitBinary().utf8());
            harness.sendText("trigger-ping");
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
            assertEquals("ping-okhttp", pongPayload.get());
        } finally {
            if (harness != null) {
                harness.close();
            }
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerWithStaticRoutingHandlesTextBinaryAndPingPong() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        OkHttpWebSocketClientHarness harness = null;
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsStatic((context, rcvUp, sndDown) -> BRANCH_HANDSHAKE);
                routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                    branchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                    branchCtx.addLastDecoder("ws-route-switch", switchRouteOnHandshake(BRANCH_WEBSOCKET));
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                    branchCtx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                    branchCtx.addLastDecoder("ws-echo", webSocketBusinessHandler());
                });
                ctx.addLast("ws-route", routing.build());
            }, SoConfig.TCP());
            harness = OkHttpWebSocketClientHarness.connect(port, "/chat");
            harness.awaitOpen();
            harness.sendText("hello-neta");
            assertEquals("[Echo] hello-neta", harness.awaitText());
            harness.sendBinary("ABCD");
            assertEquals("ABCD", harness.awaitBinary().utf8());
            harness.sendText("trigger-ping");
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
            assertEquals("ping-okhttp", pongPayload.get());
        } finally {
            if (harness != null) {
                harness.close();
            }
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerWithWebSocketOnlyAutoModeHandlesTextBinaryAndPingPong() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        OkHttpWebSocketClientHarness harness = null;
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer());
                ctx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                ctx.addLastDecoder("ws-echo", webSocketBusinessHandler());
            }, SoConfig.TCP());
            harness = OkHttpWebSocketClientHarness.connect(port, "/chat");
            harness.awaitOpen();
            harness.sendText("hello-neta");
            assertEquals("[Echo] hello-neta", harness.awaitText());
            harness.sendBinary("ABCD");
            assertEquals("ABCD", harness.awaitBinary().utf8());
            harness.sendText("trigger-ping");
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
            assertEquals("ping-okhttp", pongPayload.get());
        } finally {
            if (harness != null) {
                harness.close();
            }
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerWithWebSocketOnlyManualModeHandlesTextBinaryAndPingPong() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        OkHttpWebSocketClientHarness harness = null;
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                ctx.addLastDecoder("ws-event-tail", relayOutboundEvents());
                ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                ctx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                ctx.addLastDecoder("ws-echo", webSocketBusinessHandler());
            }, SoConfig.TCP());
            harness = OkHttpWebSocketClientHarness.connect(port, "/chat");
            harness.awaitOpen();
            harness.sendText("hello-neta");
            assertEquals("[Echo] hello-neta", harness.awaitText());
            harness.sendBinary("ABCD");
            assertEquals("ABCD", harness.awaitBinary().utf8());
            harness.sendText("trigger-ping");
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
            assertEquals("ping-okhttp", pongPayload.get());
        } finally {
            if (harness != null) {
                harness.close();
            }
            neta.shutdown();
        }
    }
}
