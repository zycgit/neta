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
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketMessageDuplexerTest extends AbstractWebSocketTest {
    private static final String WS_URI = "ws://example.com/chat";

    private void completeHandshake(VirtualPipe pipe) throws Throwable {
        pipe.client().sendData(WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI), "ws-client").get();
        assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));
        assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
        assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
    }

    @Test
    public void testDuplexerCanAggregateInboundFragmentedMessage() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer(true, 1024, 0));
            }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.textFrame(false, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("Hel"))).get();
            pipe.client().sendData(WebSocketUtils.continuationFrame(false, true, new byte[] { 0x05, 0x06, 0x07, 0x08 }, ascii("lo-"))).get();
            pipe.client().sendData(WebSocketUtils.continuationFrame(true, true, new byte[] { 0x09, 0x0A, 0x0B, 0x0C }, ascii("world"))).get();

            assertTrue(waitUntil(() -> !pipe.serverInbound().isEmpty(), 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());

            assertEquals(1, result.size());
            WebSocketMessage message = (WebSocketMessage) result.get(0);
            assertEquals(WebSocketOpcode.TEXT, message.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, message.sequence());
            assertEquals("Hello-world", text(message.content().copy()));
        });
    }

    @Test
    public void testDuplexerCanAutoFragmentOutboundMessage() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer(2));
            }, VrtTransfer.direct());

            completeHandshake(pipe);

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
    public void testDuplexerCanStreamSingleLargeInboundFrameWithoutAggregation() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13, 3));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer(false, Integer.MAX_VALUE, 0));
            }, VrtTransfer.direct());

            completeHandshake(pipe);

            pipe.client().sendData(WebSocketUtils.textFrame(true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ascii("ABCDEFG"))).get();

            assertTrue(waitUntil(() -> pipe.serverInbound().size() >= 3, 1000L));
            List<HttpObject> result = drainQueue(pipe.serverInbound());
            assertEquals(3, result.size());

            WebSocketMessage first = (WebSocketMessage) result.get(0);
            WebSocketMessage second = (WebSocketMessage) result.get(1);
            WebSocketMessage third = (WebSocketMessage) result.get(2);
            assertEquals(WebSocketOpcode.TEXT, first.type());
            assertEquals(WebSocketMessage.START_SEQUENCE, first.sequence());
            assertEquals(3, first.payloadLength());
            assertEquals("ABC", text(first.content().copy()));
            assertEquals(WebSocketOpcode.TEXT, second.type());
            assertEquals(1, second.sequence());
            assertEquals(3, second.payloadLength());
            assertEquals("DEF", text(second.content().copy()));
            assertEquals(WebSocketOpcode.TEXT, third.type());
            assertEquals(WebSocketMessage.FINAL_SEQUENCE, third.sequence());
            assertEquals(1, third.payloadLength());
            assertEquals("G", text(third.content().copy()));
        });
    }
}