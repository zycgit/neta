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
    private ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> relayOutboundEvents() {
        return new ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
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

            @Override
            public ProtoStatus onMessage(ProtoContext context, boolean isRcv,          //
                    ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
                    ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) {
                if (isRcv) {
                    while (rcvUp.hasMore()) {
                        rcvDown.offerMessage(rcvUp.takeMessage());
                    }
                } else {
                    while (sndUp.hasMore()) {
                        sndDown.offerMessage(sndUp.takeMessage());
                    }
                }
                return ProtoStatus.Next;
            }
        };
    }

    private void completeHandshake(VirtualPipe pipe, WebSocketVersion version) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(version, "/chat"), "ws-client").get();
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));
        assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
        assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
    }

    @Test
    public void testSingleTextMessageBecomesFinalTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

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
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

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
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

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
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V0));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V0));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V0));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V0));
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V0);

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
            VirtualPipe pipe = openVirtualPipe(neta, //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

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
            VirtualPipe pipe = openVirtualPipe(neta, //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

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

    @Test
    public void testControlFrameCanInterleaveWithFragmentedMessageStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.server().sendData(WebSocketUtils.textMessage(0, ascii("A"))).get();
            pipe.server().fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("!")));
            pipe.server().sendData(WebSocketUtils.textMessage(-1, ascii("B"))).get();
            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 3, 1000L));

            List<HttpObject> result = drainQueue(pipe.clientInbound());
            assertEquals(3, result.size());

            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            WebSocketFrame third = (WebSocketFrame) result.get(2);
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isFinalFragment());
            assertEquals("A", text(first));
            assertEquals(WebSocketOpcode.PING, second.opcode());
            assertTrue(second.isFinalFragment());
            assertEquals("!", text(second));
            assertEquals(WebSocketOpcode.CONTINUATION, third.opcode());
            assertTrue(third.isFinalFragment());
            assertEquals("B", text(third));
        });
    }

    @Test
    public void testFinalTextMessageCanBeAutoFragmentedIntoFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler(2));
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.server().sendData(WebSocketUtils.textMessage(ascii("ABCDEF"))).get();
            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 3, 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());

            assertEquals(3, result.size());
            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            WebSocketFrame third = (WebSocketFrame) result.get(2);
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isFinalFragment());
            assertEquals("AB", text(first));
            assertEquals(WebSocketOpcode.CONTINUATION, second.opcode());
            assertFalse(second.isFinalFragment());
            assertEquals("CD", text(second));
            assertEquals(WebSocketOpcode.CONTINUATION, third.opcode());
            assertTrue(third.isFinalFragment());
            assertEquals("EF", text(third));
        });
    }

    @Test
    public void testManualFragmentStreamBypassesAutoFragmentation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler(1));
                        ctx.addLast("ws-event-tail", relayOutboundEvents());
                    }, VrtTransfer.direct());

            completeHandshake(pipe, WebSocketVersion.V13);

            pipe.server().sendData(WebSocketUtils.textMessage(0, ascii("AB"))).get();
            pipe.server().sendData(WebSocketUtils.textMessage(-1, ascii("CD"))).get();
            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 2, 1000L));
            List<HttpObject> result = drainQueue(pipe.clientInbound());

            assertEquals(2, result.size());
            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isFinalFragment());
            assertEquals("AB", text(first));
            assertEquals(WebSocketOpcode.CONTINUATION, second.opcode());
            assertTrue(second.isFinalFragment());
            assertEquals("CD", text(second));
        });
    }

}