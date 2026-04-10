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
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.DefaultHttpByteBuf;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketFrameDecoderTest extends AbstractWebSocketTest {
    @Test
    public void testFrameDecoderNoArgFallsBackToV13ForRfc6455TextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, "Hello".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertTrue(frame.isFinalFragment());
            assertFalse(frame.isRsv1());
            assertFalse(frame.isRsv2());
            assertFalse(frame.isRsv3());
            assertFalse(frame.isMasked());
            assertNull(frame.maskingKey());
            assertEquals("Hello", text(frame));
        });
    }

    @Test
    public void testFrameDecoderUnmasksMaskedTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] maskKey = { 0x37, (byte) 0xFA, 0x21, 0x3D };
            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, true, maskKey, "Masked".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertTrue(frame.isMasked());
            assertArrayEquals(maskKey, frame.maskingKey());
            assertEquals("Masked", text(frame));
        });
    }

    @Test
    public void testFrameDecoderDoesNotConsumePartialFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, "Hello".getBytes(StandardCharsets.UTF_8));
            byte[] firstHalf = new byte[3];
            byte[] secondHalf = new byte[rfc6455Frame.length - firstHalf.length];
            System.arraycopy(rfc6455Frame, 0, firstHalf, 0, firstHalf.length);
            System.arraycopy(rfc6455Frame, firstHalf.length, secondHalf, 0, secondHalf.length);

            assertTrue(receiveAndIntBound(pipe, httpByteBuf(firstHalf)).isEmpty());

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(secondHalf));
            assertEquals(1, result.size());
            assertEquals("Hello", text((WebSocketFrame) result.get(0)));
        });
    }

    @Test
    public void testFrameDecoderParsesExtended16BitPayloadLength() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] payload = new byte[130];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) ('a' + (i % 26));
            }

            byte[] rfc6455Frame = buildRfc6455Frame(0x02, true, false, null, payload);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.BINARY, frame.opcode());
            assertEquals(payload.length, frame.content().readableBytes());
            byte[] actual = new byte[payload.length];
            frame.content().getBytes(0, actual, 0, actual.length);
            assertArrayEquals(payload, actual);
        });
    }

    @Test
    public void testFrameDecoderCanStreamSingleLargeFrameAsSyntheticFragmentSequence() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13, 3));
            }, VrtSoConfig.asServer());

            byte[] payload = "ABCDEFG".getBytes(StandardCharsets.UTF_8);
            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, payload);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));

            assertEquals(3, result.size());
            WebSocketFrame first = (WebSocketFrame) result.get(0);
            WebSocketFrame second = (WebSocketFrame) result.get(1);
            WebSocketFrame third = (WebSocketFrame) result.get(2);
            assertEquals(WebSocketOpcode.TEXT, first.opcode());
            assertFalse(first.isFinalFragment());
            assertFalse(first.isRsv1());
            assertEquals(3, first.payloadLength());
            assertEquals("ABC", text(first));
            assertEquals(WebSocketOpcode.CONTINUATION, second.opcode());
            assertFalse(second.isFinalFragment());
            assertEquals(3, second.payloadLength());
            assertEquals("DEF", text(second));
            assertEquals(WebSocketOpcode.CONTINUATION, third.opcode());
            assertTrue(third.isFinalFragment());
            assertEquals(1, third.payloadLength());
            assertEquals("G", text(third));
        });
    }

    @Test
    public void testFrameDecoderParsesPingFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x09, true, false, null, new byte[0]);
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.PING, frame.opcode());
            assertEquals(0, frame.content().readableBytes());
        });
    }

    @Test
    public void testFrameDecoderOnProtocolViolationResetsStateAndKeepsNextFrameDecodable() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame1 = buildRfc6455Frame(0x03, true, false, null, "bad".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> broken = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame1));
            assertTrue(broken.isEmpty());

            byte[] rfc6455Frame2 = buildRfc6455Frame(0x01, true, false, null, "ok".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> recovered = receiveAndIntBound(pipe, httpByteBuf(rfc6455Frame2));
            assertEquals(1, recovered.size());
            assertEquals("ok", text((WebSocketFrame) recovered.get(0)));
        });
    }

    @Test
    public void testFrameDecoderRejectsUnmaskedClientFrameWhenRunningAsServer() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asServer());

            byte[] broken = buildRfc6455Frame(0x01, true, false, null, "bad".getBytes(StandardCharsets.UTF_8));
            assertTrue(receiveAndIntBound(pipe, httpByteBuf(broken)).isEmpty());

            byte[] ok = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "ok".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> recovered = receiveAndIntBound(pipe, httpByteBuf(ok));
            assertEquals(1, recovered.size());
            assertEquals("ok", text((WebSocketFrame) recovered.get(0)));
        });
    }

    @Test
    public void testFrameDecoderRejectsMaskedServerFrameWhenRunningAsClient() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.client(WebSocketVersion.V13, "/chat"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asClient());

            byte[] broken = buildRfc6455Frame(0x01, true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, "bad".getBytes(StandardCharsets.UTF_8));
            assertTrue(receiveAndIntBound(pipe, httpByteBuf(broken)).isEmpty());

            byte[] ok = buildRfc6455Frame(0x01, true, false, null, "ok".getBytes(StandardCharsets.UTF_8));
            List<HttpObject> recovered = receiveAndIntBound(pipe, httpByteBuf(ok));
            assertEquals(1, recovered.size());
            assertEquals("ok", text((WebSocketFrame) recovered.get(0)));
        });
    }

    @Test
    public void testFrameDecoderCanStartStreaming64BitPayloadBeforeReceivingFullBody() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asServer());

            byte[] headerOnly = new byte[] { (byte) 0x81, (byte) 0xFF, 0x00, 0x00, 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04 };
            assertTrue(receiveAndIntBound(pipe, httpByteBuf(headerOnly)).isEmpty());

            byte[] partialBody = new byte[] { 0x60, 0x60, 0x60 };
            List<HttpObject> streamed = receiveAndIntBound(pipe, httpByteBuf(partialBody));
            assertEquals(1, streamed.size());
            WebSocketFrame firstChunk = (WebSocketFrame) streamed.get(0);
            assertEquals(WebSocketOpcode.TEXT, firstChunk.opcode());
            assertFalse(firstChunk.isFinalFragment());
            assertEquals(3, firstChunk.payloadLength());
            assertEquals("abc", text(firstChunk));
        });
    }

    @Test
    public void testFrameDecoderNoArgUsesHandshakeContextVersion() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V0, "/auto"));
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
            }, VrtSoConfig.asServer());

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(buildHixieTextFrame("Hixie")));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertTrue(frame.isFinalFragment());
            assertFalse(frame.isMasked());
            assertEquals("Hixie", text(frame));
        });
    }

    @Test
    public void testFrameDecoderParsesMultipleFramesFromSingleBuffer() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] first = buildRfc6455Frame(0x01, true, false, null, "First".getBytes(StandardCharsets.UTF_8));
            byte[] second = buildRfc6455Frame(0x01, true, false, null, "Second".getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[first.length + second.length];
            System.arraycopy(first, 0, combined, 0, first.length);
            System.arraycopy(second, 0, combined, first.length, second.length);

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(combined));
            assertEquals(2, result.size());
            assertEquals("First", text((WebSocketFrame) result.get(0)));
            assertEquals("Second", text((WebSocketFrame) result.get(1)));
        });
    }

    @Test
    public void testFrameDecoderV0ParsesCloseFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V0));
            }, VrtSoConfig.asServer());

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(new byte[] { (byte) 0xFF, 0x00 }));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.CLOSE, frame.opcode());
            assertEquals(0, frame.content().readableBytes());
        });
    }

    @Test
    public void testFrameDecoderV0ParsesBinaryFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V0));
            }, VrtSoConfig.asServer());

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(new byte[] { (byte) 0x80, 0x03, 0x01, 0x02, 0x03 }));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.BINARY, frame.opcode());
            assertEquals(3, frame.content().readableBytes());
        });
    }

    @Test
    public void testFrameDecoderV0ParsesEmptyTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V0));
            }, VrtSoConfig.asServer());

            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(new byte[] { 0x00, (byte) 0xFF }));
            WebSocketFrame frame = (WebSocketFrame) result.get(0);

            assertEquals(1, result.size());
            assertEquals(WebSocketOpcode.TEXT, frame.opcode());
            assertEquals(0, frame.content().readableBytes());
        });
    }

    @Test
    public void testFrameDecoderV0ParsesMultipleTextFrames() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V0));
            }, VrtSoConfig.asServer());

            byte[] raw = new byte[] { 0x00, 'A', (byte) 0xFF, 0x00, 'B', (byte) 0xFF };
            List<HttpObject> result = receiveAndIntBound(pipe, httpByteBuf(raw));

            assertEquals(2, result.size());
            assertEquals("A", text((WebSocketFrame) result.get(0)));
            assertEquals("B", text((WebSocketFrame) result.get(1)));
        });
    }

    @Test
    public void testFrameDecoderReleasesConsumedHttpByteBufWrapper() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[] rfc6455Frame = buildRfc6455Frame(0x01, true, false, null, "owned".getBytes(StandardCharsets.UTF_8));
            ByteBuf wirePayload = ByteBufAllocator.DEFAULT.buffer(rfc6455Frame.length, Integer.MAX_VALUE);
            wirePayload.writeBytes(rfc6455Frame, 0, rfc6455Frame.length);
            wirePayload.markWriter();
            HttpByteBuf source = new DefaultHttpByteBuf(wirePayload);

            List<HttpObject> result = receiveAndIntBound(pipe, source);

            assertNull(source.content());
            assertTrue(wirePayload.isFree());
            assertEquals("owned", text((WebSocketFrame) result.get(0)));
        });
    }
}