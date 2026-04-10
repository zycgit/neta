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
import java.util.List;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketFrameDuplexerTest extends AbstractWebSocketTest {
    @Test
    public void testFrameDuplexerDefaultCtorDelegatesBothDirectionsAsV13() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer());
            }, VrtSoConfig.asServer());

            byte[] frameBytes = buildRfc6455Frame(0x01, true, false, null, "Hello".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> inbound = receiveAndIntBound(pipe, httpByteBuf(frameBytes));

            assertEquals(1, inbound.size());
            WebSocketFrame frame = (WebSocketFrame) inbound.get(0);
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertEquals("Hello", text(frame));

            List<HttpObject> outbound = sendAndOutBound(pipe, WebSocketUtils.textFrame("Hello"));

            assertEquals(1, outbound.size());
            assertTrue(outbound.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) outbound.get(0)).content());
            assertEquals((byte) 0x81, wire[0]);
            assertEquals((byte) 0x05, wire[1]);
            assertEquals("Hello", new String(wire, 2, wire.length - 2, StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testFrameDuplexerConfiguredVersionIsSharedByDecoderAndEncoder() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V0));
            }, VrtSoConfig.asClient());

            List<HttpObject> inbound = receiveAndIntBound(pipe, httpByteBuf(buildHixieTextFrame("Hixie")));
            assertEquals(1, inbound.size());
            WebSocketFrame frame = (WebSocketFrame) inbound.get(0);
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertEquals("Hixie", text(frame));

            List<HttpObject> outbound = sendAndOutBound(pipe, WebSocketUtils.closeFrame(false, null, net.hasor.neta.bytebuf.ByteBuf.EMPTY));
            assertEquals(1, outbound.size());
            assertArrayEquals(new byte[] { (byte) 0xFF, 0x00 }, bytes(((HttpByteBuf) outbound.get(0)).content()));
        });
    }
}