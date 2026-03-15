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
import java.util.List;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketOutboundHandlerTest extends AbstractWebSocketTest {
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String BRANCH_WEBSOCKET = "websocket";

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

    private ProtoHandler<HttpObject, HttpObject> handshakeBridge(ProtoContext[] contextRef) {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public void onActive(ProtoContext context) {
                contextRef[0] = context;
            }

            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getData() instanceof WebSocketHandshakeEvent) {
                    WebSocketContext webSocketContext = context.context(WebSocketContext.class);
                    if (webSocketContext != null) {
                        context.rootContext(WebSocketContext.class, webSocketContext);
                    }
                    ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
                    if (routingControl != null) {
                        routingControl.switchRoute(BRANCH_WEBSOCKET);
                    }
                }
                return true;
            }
        };
    }

    private boolean hasReadyContext(ProtoContext[] contextRef) {
        WebSocketContext webSocketContext = contextRef[0] != null ? contextRef[0].context(WebSocketContext.class) : null;
        return webSocketContext != null && webSocketContext.isReady();
    }

    private void completeHandshake(VirtualPipe pipe, WebSocketVersion version, ProtoContext[] clientContextRef, ProtoContext[] serverContextRef) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(version, "/chat")).get();
        assertTrue(waitUntil(() -> hasReadyContext(clientContextRef) && hasReadyContext(serverContextRef), 1000L));
        assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
        assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
    }

    private ProtoInitializer clientInitializer(WebSocketVersion version, ProtoContext[] contextRef, boolean outboundEnabled) {
        return ctx -> {
            ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsStatic((context, rcvUp, sndDown) -> {
                return BRANCH_HANDSHAKE;
            });
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, version));
                branchCtx.addLastDecoder("bridge", handshakeBridge(contextRef));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(version));
                if (outboundEnabled) {
                    branchCtx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                    branchCtx.addLastDecoder("ws-event-tail", relayOutboundEvents());
                }
            });
            ctx.addLast("ws-route", routing.build());
        };
    }

    private ProtoInitializer serverInitializer(WebSocketVersion version, ProtoContext[] contextRef, boolean outboundEnabled) {
        return ctx -> {
            ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsStatic((context, rcvUp, sndDown) -> {
                return BRANCH_HANDSHAKE;
            });
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("ws-server", new WebSocketHandshakeDuplexer(true, version));
                branchCtx.addLastDecoder("bridge", handshakeBridge(contextRef));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(version));
                if (outboundEnabled) {
                    branchCtx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                    branchCtx.addLastDecoder("ws-event-tail", relayOutboundEvents());
                }
            });
            ctx.addLast("ws-route", routing.build());
        };
    }

    @Test
    public void testSingleTextMessageBecomesFinalTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta,//
                    clientInitializer(WebSocketVersion.V13, clientContextRef, false),//
                    serverInitializer(WebSocketVersion.V13, serverContextRef, true), VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13, clientContextRef, serverContextRef);

            pipe.server().sendData(WebSocketUtils.textMessage(ascii("hello"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());
            assertEquals(1, result.size());
            WebSocketFrame frame = (WebSocketFrame) result.get(0);
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertTrue(frame.isFinalFragment());
            assertEquals("hello", text(frame));
        });
    }

    @Test
    public void testFragmentedTextMessageStreamBecomesContinuationFrames() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta,//
                    clientInitializer(WebSocketVersion.V13, clientContextRef, false),//
                    serverInitializer(WebSocketVersion.V13, serverContextRef, true), //
                    VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13, clientContextRef, serverContextRef);

            pipe.server().sendData(WebSocketUtils.textMessage(0, ascii("A"))).get();
            pipe.server().sendData(WebSocketUtils.textMessage(1, ascii("B"))).get();
            pipe.server().sendData(WebSocketUtils.textMessage(-1, ascii("C"))).get();
            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 3, 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());

            assertEquals(3, result.size());
            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame middle = (WebSocketFrame) result.get(1);
            WebSocketFrame last = (WebSocketFrame) result.get(2);
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isFinalFragment());
            assertEquals(WebSocketOpcode.CONTINUATION, middle.opcode());
            assertFalse(middle.isFinalFragment());
            assertEquals(WebSocketOpcode.CONTINUATION, last.opcode());
            assertTrue(last.isFinalFragment());
        });
    }

    @Test
    public void testClientModeMasksMessageFrames() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta,//
                    clientInitializer(WebSocketVersion.V13, clientContextRef, true), //
                    serverInitializer(WebSocketVersion.V13, serverContextRef, false),//
                    VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13, clientContextRef, serverContextRef);

            pipe.client().sendData(WebSocketUtils.binaryMessage(ascii("data"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            assertEquals(1, result.size());
            WebSocketFrame frame = (WebSocketFrame) result.get(0);
            assertTrue(frame.isMasked());
            assertNotNull(frame.maskingKey());
        });
    }

    @Test
    public void testAutoDetectedV0ClientDoesNotMaskMessageFrames() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta,//
                    clientInitializer(WebSocketVersion.V0, clientContextRef, true), //
                    serverInitializer(WebSocketVersion.V0, serverContextRef, false),//
                    VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V0, clientContextRef, serverContextRef);

            pipe.client().sendData(WebSocketUtils.binaryMessage(ascii("data"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            assertEquals(1, result.size());
            WebSocketFrame frame = (WebSocketFrame) result.get(0);
            assertFalse(frame.isMasked());
            assertNull(frame.maskingKey());
        });
    }

    @Test
    public void testPingEventBecomesPingFrame() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta, //
                    clientInitializer(WebSocketVersion.V13, clientContextRef, false), //
                    serverInitializer(WebSocketVersion.V13, serverContextRef, true), VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13, clientContextRef, serverContextRef);

            pipe.server().fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("hello")));
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());
            assertEquals(1, result.size());
            WebSocketFrame frame = (WebSocketFrame) result.get(0);
            assertEquals(WebSocketOpcode.PING, frame.opcode());
            assertTrue(frame.isFinalFragment());
            assertEquals("hello", text(frame));
        });
    }

    @Test
    public void testPongEventBecomesPongFrame() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoContext[] clientContextRef = new ProtoContext[1];
            ProtoContext[] serverContextRef = new ProtoContext[1];
            VirtualPipe pipe = openVirtualPipe(neta, //
                    clientInitializer(WebSocketVersion.V13, clientContextRef, false), //
                    serverInitializer(WebSocketVersion.V13, serverContextRef, true), VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13, clientContextRef, serverContextRef);

            pipe.server().fireUserEvent(PongWebSocketEvent.class, WebSocketUtils.pongEvent(ascii("hello")));
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());
            assertEquals(1, result.size());
            WebSocketFrame frame = (WebSocketFrame) result.get(0);
            assertEquals(WebSocketOpcode.PONG, frame.opcode());
            assertTrue(frame.isFinalFragment());
            assertEquals("hello", text(frame));
        });
    }
}