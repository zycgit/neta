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
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketClientServerFlowTest extends AbstractWebSocketTest {
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String BRANCH_WEBSOCKET = HttpRouteKey.BRANCH_SOCKET;
    private static final String WS_PATH          = "/chat";

    private static PongWebSocketEvent pongEvent(Iterable<SoUserEvent> events) {
        if (events == null) {
            return null;
        }
        for (SoUserEvent event : events) {
            if (event != null && event.getData() instanceof PongWebSocketEvent) {
                return (PongWebSocketEvent) event.getData();
            }
        }
        return null;
    }

    private WebSocketContext webSocketContext(SoChannel<?> channel) {
        return channel.findProtoContext(WebSocketContext.class);
    }

    private boolean hasReadyContext(SoChannel<?> channel) {
        WebSocketContext webSocketContext = webSocketContext(channel);
        return webSocketContext != null && webSocketContext.isReady();
    }

    private VirtualPipe openWebSocketPipe(NetManager neta, WebSocketVersion version) throws Throwable {
        return openVirtualPipe(neta, ctx -> {
            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsStatic(new ProtoRoutingDataSelector<Object, Object>() {
                @Override
                public String route(ProtoContext context, ProtoRcvQueue<Object> rcvUp, ProtoSndQueue<Object> sndDown) {
                    return BRANCH_HANDSHAKE;
                }
            });
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("client-ws-handshake", new WebSocketHandshakeDuplexer(false, version));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("client-ws-frame", new WebSocketFrameDuplexer(version));
                branchCtx.addLast("client-ws-message", new WebSocketMessageDuplexer());
            });
            ctx.addLast("client-ws-route", routing.build());
        }, ctx -> {
            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsStatic(new ProtoRoutingDataSelector<Object, Object>() {
                @Override
                public String route(ProtoContext context, ProtoRcvQueue<Object> rcvUp, ProtoSndQueue<Object> sndDown) {
                    return BRANCH_HANDSHAKE;
                }
            });
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("server-ws-handshake", new WebSocketHandshakeDuplexer(true, version));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("server-ws-frame", new WebSocketFrameDuplexer(version));
                branchCtx.addLast("server-ws-message", new WebSocketMessageDuplexer());
            });
            ctx.addLast("server-ws-route", routing.build());
        }, VrtTransfer.direct());
    }

    private void completeHandshake(VirtualPipe pipe, WebSocketVersion version) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(version, WS_PATH)).get();
        assertTrue(waitUntil(() -> hasReadyContext(pipe.client()) && hasReadyContext(pipe.server()), 1000L));

        WebSocketContext clientContext = webSocketContext(pipe.client());
        WebSocketContext serverContext = webSocketContext(pipe.server());
        assertNotNull(clientContext);
        assertNotNull(serverContext);
        assertEquals(version.code(), clientContext.version());
        assertEquals(version.code(), serverContext.version());
        assertEquals(WS_PATH, clientContext.requestPath());
        assertEquals(WS_PATH, serverContext.requestPath());

        assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
        assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
    }

    @Test
    public void testClientMessageArrivesAsServerMessageAndServerReplyArrivesAsClientMessage() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openWebSocketPipe(neta, WebSocketVersion.V13);
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().sendData(WebSocketUtils.textMessage(ascii("hello-server"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            assertEquals(1, serverBatch.size());
            assertTrue(serverBatch.get(0) instanceof TextWebSocketMessage);
            WebSocketMessage serverMessage = (WebSocketMessage) serverBatch.get(0);
            assertEquals(WebSocketOpcode.TEXT, serverMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, serverMessage.sequence());
            assertEquals("hello-server", text(serverMessage.content()));

            pipe.server().sendData(WebSocketUtils.textMessage(ascii("hello-client"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertEquals(1, clientBatch.size());
            assertTrue(clientBatch.get(0) instanceof TextWebSocketMessage);
            WebSocketMessage clientMessage = (WebSocketMessage) clientBatch.get(0);
            assertEquals(WebSocketOpcode.TEXT, clientMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, clientMessage.sequence());
            assertEquals("hello-client", text(clientMessage.content()));
        });
    }

    @Test
    public void testClientBinaryMessageAndServerBinaryMessageFlowAcrossFullPipe() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openWebSocketPipe(neta, WebSocketVersion.V13);
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().sendData(WebSocketUtils.binaryMessage(ascii("ABCD"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            assertEquals(1, serverBatch.size());
            assertTrue(serverBatch.get(0) instanceof BinaryWebSocketMessage);
            WebSocketMessage serverMessage = (WebSocketMessage) serverBatch.get(0);
            assertEquals(WebSocketOpcode.BINARY, serverMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, serverMessage.sequence());
            assertEquals("ABCD", text(serverMessage.content()));

            pipe.server().sendData(WebSocketUtils.binaryMessage(ascii("WXYZ"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertEquals(1, clientBatch.size());
            assertTrue(clientBatch.get(0) instanceof BinaryWebSocketMessage);
            WebSocketMessage clientMessage = (WebSocketMessage) clientBatch.get(0);
            assertEquals(WebSocketOpcode.BINARY, clientMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, clientMessage.sequence());
            assertEquals("WXYZ", text(clientMessage.content()));
        });
    }

    @Test
    public void testClientPingEventProducesClientSidePongEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openWebSocketPipe(neta, WebSocketVersion.V13);
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-body")));
            assertTrue(waitUntil(() -> pongEvent(pipe.clientUserEvents()) != null, 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertTrue(serverBatch.isEmpty());
            assertTrue(clientBatch.isEmpty());
            PongWebSocketEvent clientEvent = pongEvent(pipe.clientUserEvents());
            assertNotNull(clientEvent);
            assertEquals("ping-body", clientEvent.content().readString(clientEvent.content().readableBytes(), java.nio.charset.StandardCharsets.US_ASCII));
            clientEvent.release();
        });
    }
}