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

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import net.hasor.neta.codec.http.HttpObject;

public class WebSocketClientServerFlowTest extends AbstractWebSocketTest {
    private static final String WS_PATH = "/chat";
    private static final String WS_URI  = "ws://example.com" + WS_PATH;

    private static PongWebSocketEvent pongEvent(Iterable<SoEvent> events) {
        if (events == null) {
            return null;
        }
        for (SoEvent event : events) {
            if (event != null && event.getData() instanceof PongWebSocketEvent) {
                return (PongWebSocketEvent) event.getData();
            }
        }
        return null;
    }

    private WebSocketContext webSocketContext(SoChannel<?> channel) {
        return WebSocketRegistry.resolve(channel);
    }

    private void completeHandshake(VirtualPipe pipe, WebSocketVersion version) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(version, WS_URI), "ws-client").get();
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

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
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, VrtTransfer.direct());
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().sendData(WebSocketUtils.textMessage(ascii("hello-server"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            assertEquals(1, serverBatch.size());
            assertTrue(serverBatch.get(0) instanceof TextWebSocketMessage);
            WebSocketMessage serverMessage = (WebSocketMessage) serverBatch.get(0);
            assertEquals(WebSocketOpcode.TEXT, serverMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, serverMessage.sequence());
            assertEquals("hello-server", text(serverMessage.content().copy()));

            pipe.server().sendData(WebSocketUtils.textMessage(ascii("hello-client"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertEquals(1, clientBatch.size());
            assertTrue(clientBatch.get(0) instanceof TextWebSocketMessage);
            WebSocketMessage clientMessage = (WebSocketMessage) clientBatch.get(0);
            assertEquals(WebSocketOpcode.TEXT, clientMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, clientMessage.sequence());
            assertEquals("hello-client", text(clientMessage.content().copy()));
        });
    }

    @Test
    public void testClientBinaryMessageAndServerBinaryMessageFlowAcrossFullPipe() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, VrtTransfer.direct());
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().sendData(WebSocketUtils.binaryMessage(ascii("ABCD"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            assertEquals(1, serverBatch.size());
            assertTrue(serverBatch.get(0) instanceof BinaryWebSocketMessage);
            WebSocketMessage serverMessage = (WebSocketMessage) serverBatch.get(0);
            assertEquals(WebSocketOpcode.BINARY, serverMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, serverMessage.sequence());
            assertEquals("ABCD", text(serverMessage.content().copy()));

            pipe.server().sendData(WebSocketUtils.binaryMessage(ascii("WXYZ"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertEquals(1, clientBatch.size());
            assertTrue(clientBatch.get(0) instanceof BinaryWebSocketMessage);
            WebSocketMessage clientMessage = (WebSocketMessage) clientBatch.get(0);
            assertEquals(WebSocketOpcode.BINARY, clientMessage.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, clientMessage.sequence());
            assertEquals("WXYZ", text(clientMessage.content().copy()));
        });
    }

    @Test
    public void testClientPingEventProducesClientSidePongEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplex(WebSocketVersion.V13));
                ctx.addLast("ws-ext", new WebSocketExtensionDuplex());
                ctx.addLast("ws-message", new WebSocketMessageDuplex());
            }, VrtTransfer.direct());
            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.client().fireEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-body")));
            assertTrue(waitUntil(() -> pongEvent(pipe.clientEvents()) != null, 1000L));

            List<HttpObject> serverBatch = drainQueue(pipe.serverInbound());
            List<HttpObject> clientBatch = drainQueue(pipe.clientInbound());
            assertTrue(serverBatch.isEmpty());
            assertTrue(clientBatch.isEmpty());
            PongWebSocketEvent clientEvent = pongEvent(pipe.clientEvents());
            assertNotNull(clientEvent);
            assertEquals("ping-body", clientEvent.content().readString(clientEvent.content().readableBytes(), java.nio.charset.StandardCharsets.US_ASCII));
            clientEvent.release();
        });
    }
}