/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;

public class WebSocketFrameEncoderTest extends AbstractWebSocketTest {
    @Test
    public void testFrameEncoderNoArgFallsBackToV13ForTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asClient());

            WebSocketFrame frame = WebSocketUtils.textFrame("Hello");
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x81, wire[0]);
            assertEquals((byte) 0x05, wire[1]);
            assertEquals("Hello", new String(wire, 2, wire.length - 2, StandardCharsets.UTF_8));
            assertNull(frame.content());
        });
    }

    @Test
    public void testFrameEncoderEncodesMaskedTextFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            byte[] maskKey = { 0x37, (byte) 0xFA, 0x21, 0x3D };
            byte[] payload = "Mask".getBytes(StandardCharsets.UTF_8);
            WebSocketFrame frame = WebSocketUtils.textFrame(true, true, maskKey, ByteBuf.wrap(payload));

            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x81, wire[0]);
            assertEquals((byte) 0x84, wire[1]);
            assertArrayEquals(maskKey, new byte[] { wire[2], wire[3], wire[4], wire[5] });

            byte[] maskedPayload = new byte[payload.length];
            System.arraycopy(wire, 6, maskedPayload, 0, payload.length);
            for (int i = 0; i < maskedPayload.length; i++) {
                maskedPayload[i] = (byte) (maskedPayload[i] ^ maskKey[i & 3]);
            }
            assertArrayEquals(payload, maskedPayload);
            assertNull(frame.content());
        });
    }

    @Test
    public void testFrameEncoderUsesExtended16BitPayloadLength() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            byte[] payload = new byte[130];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) ('a' + (i % 26));
            }
            WebSocketFrame frame = WebSocketUtils.binaryFrame(payload);

            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x82, wire[0]);
            assertEquals((byte) 126, wire[1]);
            int length = ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF);
            assertEquals(payload.length, length);
            assertEquals(payload.length + 4, wire.length);
        });
    }

    @Test
    public void testFrameEncoderEncodesPingFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            List<HttpObject> res = sendAndOutBound(pipe, WebSocketUtils.pingFrame());
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x89, wire[0]);
            assertEquals((byte) 0x00, wire[1]);
        });
    }

    @Test
    public void testFrameEncoderNoArgUsesHandshakeContextVersion() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.client(WebSocketVersion.V0, "/auto"));
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asClient());

            WebSocketFrame frame = WebSocketUtils.textFrame("Hixie");
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x00, wire[0]);
            assertEquals((byte) 0xFF, wire[wire.length - 1]);
            assertEquals("Hixie", new String(wire, 1, wire.length - 2, StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testFrameEncoderV0EncodesCloseFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V0));
            }, VrtSoConfig.asClient());

            WebSocketFrame frame = WebSocketUtils.closeFrame(false, null, ByteBuf.EMPTY);
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertArrayEquals(new byte[] { (byte) 0xFF, 0x00 }, wire);
        });
    }

    @Test
    public void testFrameEncoderV0EncodesBinaryFrame() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V0));
            }, VrtSoConfig.asClient());

            byte[] payload = { 0x01, 0x02, 0x03 };
            WebSocketFrame frame = WebSocketUtils.binaryFrame(payload);
            List<HttpObject> res = sendAndOutBound(pipe, frame);
            assertEquals(1, res.size());
            assertTrue(res.get(0) instanceof HttpByteBuf);

            byte[] wire = bytes(((HttpByteBuf) res.get(0)).content());
            assertEquals((byte) 0x80, wire[0]);
            assertEquals((byte) 0x03, wire[1]);
            assertEquals(payload[0], wire[2]);
            assertEquals(payload[1], wire[3]);
            assertEquals(payload[2], wire[4]);
            assertFalse(frame.isMasked());
        });
    }

    @Test
    public void testFrameEncoderReleasesConsumedFrameContent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            ByteBuf payload = ByteBufAllocator.DEFAULT.buffer(16, Integer.MAX_VALUE);
            payload.writeString("Hi", StandardCharsets.UTF_8);
            payload.markWriter();
            WebSocketFrame frame = WebSocketUtils.textFrame(true, false, null, payload);

            List<HttpObject> result = sendAndOutBound(pipe, frame);

            assertNull(frame.content());
            assertTrue(payload.isFree());
            assertTrue(result.get(0) instanceof HttpByteBuf);
        });
    }

    @Test
    public void testFrameEncoderRejectsFragmentedControlFrameAndKeepsNextFrameEncodable() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            List<HttpObject> result = sendAndOutBound(pipe, WebSocketFrame.create(WebSocketOpcode.PING, false, false, null, ByteBuf.EMPTY));
            assertTrue(result.isEmpty());

            List<HttpObject> recovered = sendAndOutBound(pipe, WebSocketUtils.pingFrame());
            assertEquals(1, recovered.size());
            byte[] wire = bytes(((HttpByteBuf) recovered.get(0)).content());
            assertEquals((byte) 0x89, wire[0]);
            assertEquals((byte) 0x00, wire[1]);
        });
    }

    @Test
    public void testFrameEncoderRejectsInvalidClosePayloadAndKeepsNextFrameEncodable() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            List<HttpObject> result = sendAndOutBound(pipe, WebSocketUtils.closeFrame(false, null, ByteBuf.wrap(new byte[] { 0x01 })));
            assertTrue(result.isEmpty());

            List<HttpObject> recovered = sendAndOutBound(pipe, WebSocketUtils.pingFrame());
            assertEquals(1, recovered.size());
            byte[] wire = bytes(((HttpByteBuf) recovered.get(0)).content());
            assertEquals((byte) 0x89, wire[0]);
            assertEquals((byte) 0x00, wire[1]);
        });
    }

    @Test
    public void testFrameEncoderAllowsClientMandatoryExtensionCloseCodeWhenHandshakeContextIsReady() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.client(WebSocketVersion.V13, "/chat"));
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asClient());

            byte[] payload = new byte[] { (byte) ((WebSocketCode.MANDATORY_EXTENSION >> 8) & 0xFF), (byte) (WebSocketCode.MANDATORY_EXTENSION & 0xFF) };
            WebSocketFrame close = WebSocketUtils.closeFrame(true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap(payload));
            List<HttpObject> result = sendAndOutBound(pipe, close);

            assertEquals(1, result.size());
            byte[] wire = bytes(((HttpByteBuf) result.get(0)).content());
            assertEquals((byte) 0x88, wire[0]);
            assertEquals((byte) 0x82, wire[1]);
        });
    }

    @Test
    public void testFrameEncoderRejectsServerMandatoryExtensionCloseCodeWhenHandshakeContextIsReady() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asServer());

            byte[] payload = new byte[] { (byte) ((WebSocketCode.MANDATORY_EXTENSION >> 8) & 0xFF), (byte) (WebSocketCode.MANDATORY_EXTENSION & 0xFF) };
            List<HttpObject> result = sendAndOutBound(pipe, WebSocketUtils.closeFrame(false, null, ByteBuf.wrap(payload)));
            assertTrue(result.isEmpty());

            List<HttpObject> recovered = sendAndOutBound(pipe, WebSocketUtils.textFrame("ok"));
            assertEquals(1, recovered.size());
            byte[] wire = bytes(((HttpByteBuf) recovered.get(0)).content());
            assertEquals("ok", new String(wire, 2, wire.length - 2, StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testFrameEncoderRejectsMaskedFrameWithoutMaskKeyAndKeepsNextFrameEncodable() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            assertTrue(sendAndOutBound(pipe, WebSocketFrame.create(WebSocketOpcode.TEXT, true, true, null, ByteBuf.wrap("bad".getBytes(StandardCharsets.UTF_8)))).isEmpty());

            List<HttpObject> result = sendAndOutBound(pipe, WebSocketUtils.textFrame("ok"));
            assertEquals(1, result.size());
            byte[] wire = bytes(((HttpByteBuf) result.get(0)).content());
            assertEquals("ok", new String(wire, 2, wire.length - 2, StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testFrameEncoderRejectsUnmaskedClientFrameWhenHandshakeContextIsReady() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.client(WebSocketVersion.V13, "/chat"));
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asClient());

            assertTrue(sendAndOutBound(pipe, WebSocketUtils.textFrame("bad")).isEmpty());

            WebSocketFrame masked = WebSocketUtils.textFrame(true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap("ok".getBytes(StandardCharsets.UTF_8)));
            List<HttpObject> result = sendAndOutBound(pipe, masked);
            assertEquals(1, result.size());
            byte[] wire = bytes(((HttpByteBuf) result.get(0)).content());
            assertEquals((byte) 0x82, (byte) (wire[1] & 0x82));
        });
    }

    @Test
    public void testFrameEncoderRejectsMaskedServerFrameWhenHandshakeContextIsReady() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketRegistry.bind(ctx, WebSocketRegistryKey.connectionScope(), MockWebSocketContext.server(WebSocketVersion.V13, "/chat"));
                ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
            }, VrtSoConfig.asServer());

            WebSocketFrame masked = WebSocketUtils.textFrame(true, true, new byte[] { 0x01, 0x02, 0x03, 0x04 }, ByteBuf.wrap("bad".getBytes(StandardCharsets.UTF_8)));
            assertTrue(sendAndOutBound(pipe, masked).isEmpty());

            List<HttpObject> result = sendAndOutBound(pipe, WebSocketUtils.textFrame("ok"));
            assertEquals(1, result.size());
            byte[] wire = bytes(((HttpByteBuf) result.get(0)).content());
            assertEquals((byte) 0x02, (byte) (wire[1] & 0x82));
        });
    }
}
