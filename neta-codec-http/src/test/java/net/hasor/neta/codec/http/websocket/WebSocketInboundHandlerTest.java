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
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.SoUserEvent;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebSocketInboundHandlerTest extends AbstractWebSocketTest {
    private static int closeStatusCode(WebSocketFrame frame) {
        return ((frame.content().getByte(0) & 0xFF) << 8) | (frame.content().getByte(1) & 0xFF);
    }

    private ProtoHandler<HttpObject, HttpObject> recordEvents(List<Object> serverEvents) {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                Object eventData = event.getData();
                if (eventData instanceof AbstractWebSocketEvent && !(eventData instanceof WebSocketHandshakeEvent)) {
                    serverEvents.add(eventData);
                }
                return true;
            }
        };
    }

    private void completeHandshake(VirtualPipe pipe) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"), "ws-client").get();
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));
        assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
        assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
    }

    @Test
    public void testSingleTextFrameBecomesFinalChunk() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta,    //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.textFrame(true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("Hello"))).get();
            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());

            assertEquals(1, result.size());
            assertTrue(result.get(0) instanceof TextWebSocketMessage);
            WebSocketMessage msg = (WebSocketMessage) result.get(0);
            assertEquals(WebSocketOpcode.TEXT, msg.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, msg.sequence());
            assertEquals("Hello", text(msg.content()));
        });
    }

    @Test
    public void testFragmentedTextMessageBecomesChunkStream() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta,    //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.textFrame(false, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("Hel"))).get();
            pipe.client().sendData(WebSocketUtils.continuationFrame(false, true, new byte[] { 0x05, 0x06, 0x07, 0x08 }, ascii("lo-"))).get();
            pipe.client().sendData(WebSocketUtils.continuationFrame(true, true, new byte[] { 0x09, 0x0A, 0x0B, 0x0C }, ascii("world"))).get();
            assertTrue(waitUntil(() -> pipe.serverInbound().size() >= 3, 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());

            assertEquals(3, result.size());
            assertEquals(0, ((WebSocketMessage) result.get(0)).sequence());
            assertEquals(1, ((WebSocketMessage) result.get(1)).sequence());
            assertEquals(-1, ((WebSocketMessage) result.get(2)).sequence());
            assertEquals("Hel", text(((WebSocketMessage) result.get(0)).content()));
            assertEquals("lo-", text(((WebSocketMessage) result.get(1)).content()));
            assertEquals("world", text(((WebSocketMessage) result.get(2)).content()));
        });
    }

    @Test
    public void testPingAutoRepliesWithoutPublishingMessageOrEvent() throws Throwable {
        autoCloseNeta(neta -> {
            List<Object> serverEvents = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta,    //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                        ctx.addLastDecoder("events", recordEvents(serverEvents));
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.pingFrame(true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("hello"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            List<HttpObject> outbound = drainQueue(pipe.clientInbound());

            assertTrue(result.isEmpty());
            assertEquals(1, outbound.size());
            WebSocketFrame pong = (WebSocketFrame) outbound.get(0);
            assertEquals(WebSocketOpcode.PONG, pong.opcode());
            assertEquals("hello", text(pong));
            assertTrue(serverEvents.isEmpty());
        });
    }

    @Test
    public void testPongBecomesInboundPongEvent() throws Throwable {
        autoCloseNeta(neta -> {
            List<Object> serverEvents = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta, //
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                        ctx.addLastDecoder("events", recordEvents(serverEvents));
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.pongFrame(true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("hello"))).get();
            assertTrue(waitUntil(() -> !serverEvents.isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            List<HttpObject> outbound = drainQueue(pipe.clientInbound());

            assertTrue(result.isEmpty());
            assertTrue(outbound.isEmpty());
            assertEquals(1, serverEvents.size());
            assertTrue(serverEvents.get(0) instanceof PongWebSocketEvent);
            PongWebSocketEvent pong = (PongWebSocketEvent) serverEvents.get(0);
            assertEquals("hello", pong.content().readString(pong.content().readableBytes(), java.nio.charset.StandardCharsets.US_ASCII));
            pong.release();
        });
    }

    @Test
    public void testCloseAutoRepliesAndPublishesEvent() throws Throwable {
        autoCloseNeta(neta -> {
            List<Object> serverEvents = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                        ctx.addLastDecoder("events", recordEvents(serverEvents));
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.closeFrame(true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap(new byte[] { (byte) (WebSocketCode.NORMAL_CLOSURE >> 8), (byte) WebSocketCode.NORMAL_CLOSURE, 'b', 'y', 'e' }))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() && !serverEvents.isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            List<HttpObject> outbound = drainQueue(pipe.clientInbound());

            assertTrue(result.isEmpty());
            assertEquals(1, outbound.size());
            WebSocketFrame close = (WebSocketFrame) outbound.get(0);
            assertEquals(WebSocketOpcode.CLOSE, close.opcode());
            assertEquals(WebSocketCode.NORMAL_CLOSURE, ((close.content().getByte(0) & 0xFF) << 8) | (close.content().getByte(1) & 0xFF));
            assertEquals(1, serverEvents.size());
            assertTrue(serverEvents.get(0) instanceof WebSocketCloseEvent);
            assertEquals(WebSocketCode.NORMAL_CLOSURE, ((WebSocketCloseEvent) serverEvents.get(0)).statusCode());
        });
    }

    @Test
    public void testContinuationOutsideFragmentedMessageRepliesWithProtocolErrorCode() throws Throwable {
        autoCloseNeta(neta -> {
            List<Object> serverEvents = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                        ctx.addLastDecoder("events", recordEvents(serverEvents));
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.continuationFrame(true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("oops"))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> result = drainQueue(pipe.serverInbound());
            List<HttpObject> outbound = drainQueue(pipe.clientInbound());

            assertTrue(result.isEmpty());
            assertEquals(1, outbound.size());
            WebSocketFrame close = (WebSocketFrame) outbound.get(0);
            assertEquals(WebSocketOpcode.CLOSE, close.opcode());
            assertEquals(WebSocketCode.PROTOCOL_ERROR, closeStatusCode(close));
            assertTrue(serverEvents.isEmpty());
        });
    }

    @Test
    public void testInvalidCloseReasonRepliesWithInvalidDataCode() throws Throwable {
        autoCloseNeta(neta -> {
            List<Object> serverEvents = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta,//
                    ctx -> {
                        ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    }, ctx -> {
                        ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                        ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
                        ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
                        ctx.addLastDecoder("events", recordEvents(serverEvents));
                    }, VrtTransfer.direct());

            completeHandshake(pipe);

            byte[] payload = new byte[] { (byte) ((WebSocketCode.NORMAL_CLOSURE >> 8) & 0xFF), (byte) (WebSocketCode.NORMAL_CLOSURE & 0xFF), (byte) 0xC3, (byte) 0x28 };
            pipe.client().sendData(WebSocketUtils.closeFrame(true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap(payload))).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> result = drainQueue(pipe.serverInbound());
            List<HttpObject> outbound = drainQueue(pipe.clientInbound());

            assertTrue(result.isEmpty());
            assertEquals(1, outbound.size());
            WebSocketFrame close = (WebSocketFrame) outbound.get(0);
            assertEquals(WebSocketOpcode.CLOSE, close.opcode());
            assertEquals(WebSocketCode.INVALID_DATA, closeStatusCode(close));
            assertTrue(serverEvents.isEmpty());
        });
    }
}