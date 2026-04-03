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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketFrameExtensionTest extends AbstractWebSocketTest {
    @Test
    public void testFrameCreateWithRsvBits() {
        WebSocketFrame frame = WebSocketFrame.create(WebSocketOpcode.TEXT, true, true, false, true, false, null, ByteBuf.wrap("Hi".getBytes(StandardCharsets.UTF_8)));
        assertTrue(frame.isRsv1());
        assertFalse(frame.isRsv2());
        assertTrue(frame.isRsv3());
        assertEquals("Hi", readContent(frame));
    }

    @Test
    public void testFrameDecoderPreservesRsvBitsInDecodedFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, "Hello".getBytes(StandardCharsets.UTF_8));
            rfc6455Frame[0] = (byte) (rfc6455Frame[0] | 0x40 | 0x10);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertTrue(frame.isRsv1());
            assertFalse(frame.isRsv2());
            assertTrue(frame.isRsv3());
        });
    }

    @Test
    public void testFrameDecoderKeepsRsvBitsOnlyOnFirstSyntheticSlice() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13, 3));
            }, VrtSoConfig.asServer());

            byte[] payload = "ABCDEFG".getBytes(StandardCharsets.UTF_8);
            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, payload);
            rfc6455Frame[0] = (byte) (rfc6455Frame[0] | 0x40 | 0x20);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));

            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            WebSocketFrame third = (WebSocketFrame) result.get(2);
            assertTrue(first.isRsv1());
            assertTrue(first.isRsv2());
            assertFalse(first.isRsv3());
            assertFalse(second.isRsv1());
            assertFalse(second.isRsv2());
            assertFalse(second.isRsv3());
            assertFalse(third.isRsv1());
            assertFalse(third.isRsv2());
            assertFalse(third.isRsv3());
        });
    }

    @Test
    public void testFrameDecoderRejectsRsvBitsWithoutNegotiatedExtension() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.rootContext(WebSocketContext.class, MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asServer());

            byte[] broken = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "bad".getBytes(StandardCharsets.UTF_8));
            broken[0] = (byte) (broken[0] | 0x40);
            assertTrue(receiveAndIntBound(pipe, httpByteBuf(broken)).isEmpty());

            byte[] ok = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "ok".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> recovered = receiveAndIntBound(pipe, httpByteBuf(ok));
            assertEquals(1, recovered.size());
            assertEquals("ok", text((WebSocketFrame) recovered.get(0)));
        });
    }

    @Test
    public void testFrameEncoderWritesRsvBitsToHeader() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            WebSocketFrame frame = WebSocketFrame.create(WebSocketOpcode.TEXT, true, true, false, true, false, null, ByteBuf.wrap("Hi".getBytes(StandardCharsets.UTF_8)));
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());

            assertEquals((byte) 0xD1, wire[0]);
            assertEquals((byte) 0x02, wire[1]);
        });
    }
}