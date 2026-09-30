/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.DefaultHttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketFrameDecoderUnmaskTest extends AbstractWebSocketTest {
    private static final ByteOrder[] ORDERS = { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN };
    private static final byte[][]    MASKS  = { //
            { 0x00, 0x00, 0x00, 0x00 }, //
            { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }, //
            { 0x37, (byte) 0xFA, 0x21, 0x3D }, //
            { (byte) 0x80, 0x00, 0x01, 0x7F } };

    @Test
    public void testMaskedPayloadAllWordTailsStorageOrdersAndOffsets() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            for (int length = 0; length < 32; length++) {
                byte[] payload = payload(length);
                for (int offset = 0; offset < 8; offset++) {
                    byte[] mask = MASKS[(length + offset) & 3];
                    for (ByteOrder order : ORDERS) {
                        for (int storage = 0; storage < 3; storage++) {
                            assertDecoded(pipe, payload, mask, order, storage, offset, Integer.MAX_VALUE);
                        }
                    }
                }
            }
        });
    }

    @Test
    public void testMaskedPayloadAcrossScratchAndExtendedLengthBoundaries() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            int[] lengths = { 125, 126, 127, 128, 4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 4101, 4102, 4103, 8191, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 65536, 65539 };
            for (int length : lengths) {
                for (ByteOrder order : ORDERS) {
                    for (int storage = 0; storage < 3; storage++) {
                        assertDecoded(pipe, payload(length), MASKS[length & 3], order, storage, 3, Integer.MAX_VALUE);
                    }
                }
            }
        });
    }

    @Test
    public void testMaskedStreamRotatesLongMaskAtEveryPayloadOffset() throws Throwable {
        autoCloseNeta(neta -> {
            int[] chunkLimits = { 9, 10, 11, 12, 13, 14, 15, 4093, 4095, 4097 };
            for (int chunkLimit : chunkLimits) {
                VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                    ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13, chunkLimit));
                }, VrtSoConfig.asServer());

                byte[] payload = payload(chunkLimit < 16 ? 73 : 8207);
                for (ByteOrder order : ORDERS) {
                    for (int storage = 0; storage < 3; storage++) {
                        for (byte[] mask : MASKS) {
                            assertDecoded(pipe, payload, mask, order, storage, 5, chunkLimit);
                        }
                    }
                }
            }
        });
    }

    @Test
    public void testMaskedBatchKeepsEachMaskAndInputUnchanged() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            byte[][] payloads = { payload(9), payload(14), payload(25), payload(4103), payload(8199) };
            byte[][] wires = new byte[payloads.length][];
            for (int i = 0; i < wires.length; i++) {
                wires[i] = buildRfc6455Frame(0x02, true, true, MASKS[i & 3], payloads[i]);
            }
            byte[] combined = concat(wires);
            byte[] before = combined.clone();
            List<HttpObject> result = null;
            try {
                result = receiveAndIntBound(pipe, new DefaultHttpByteBuf(ByteBuf.wrap(combined).asReadOnly()));
                assertTrue(receiveAndIntError(pipe).isEmpty());
                assertEquals(payloads.length, result.size());
                for (int i = 0; i < payloads.length; i++) {
                    WebSocketFrame frame = (WebSocketFrame) result.get(i);
                    assertEquals(WebSocketOpcode.BINARY, frame.opcode());
                    assertTrue(frame.isFinalFragment());
                    assertTrue(frame.isMasked());
                    assertArrayEquals(MASKS[i & 3], frame.maskingKey());
                    assertEquals(payloads[i].length, frame.payloadLength());
                    assertArrayEquals(payloads[i], content(frame));
                }
                assertArrayEquals(before, combined);
            } finally {
                free(result);
            }
        });
    }

    private void assertDecoded(VirtualPipe pipe, byte[] payload, byte[] mask, ByteOrder order, int storage, int offset, int chunkLimit) {
        byte[] wire = buildRfc6455Frame(0x02, true, true, mask, payload);
        byte[] padded = new byte[offset + wire.length + 8];
        Arrays.fill(padded, (byte) 0xA5);
        System.arraycopy(wire, 0, padded, offset, wire.length);
        byte[] before = padded.clone();
        ByteBuf owner = source(padded, storage);
        List<HttpObject> result = null;
        try {
            ByteBuf input = owner.slice(offset, wire.length).order(order);
            if ((offset & 1) != 0) {
                input = input.asReadOnly();
            }
            result = receiveAndIntBound(pipe, new DefaultHttpByteBuf(input));
            assertTrue(receiveAndIntError(pipe).isEmpty());
            int expectedFrames = payload.length == 0 ? 1 : (int) (((long) payload.length + chunkLimit - 1) / chunkLimit);
            assertEquals(expectedFrames, result.size());
            byte[] actual = new byte[payload.length];
            int consumed = 0;
            for (int i = 0; i < result.size(); i++) {
                WebSocketFrame frame = (WebSocketFrame) result.get(i);
                assertEquals(i == 0 ? WebSocketOpcode.BINARY : WebSocketOpcode.CONTINUATION, frame.opcode());
                assertEquals(i == result.size() - 1, frame.isFinalFragment());
                assertTrue(frame.isMasked());
                assertArrayEquals(mask, frame.maskingKey());
                int length = Math.min(chunkLimit, payload.length - consumed);
                assertEquals(length, frame.payloadLength());
                assertEquals(length, frame.content().readableBytes());
                frame.content().getBytes(0, actual, consumed, length);
                consumed += length;
            }
            assertEquals(payload.length, consumed);
            assertArrayEquals(payload, actual);
            byte[] after = new byte[before.length];
            owner.getBytes(0, after, 0, after.length);
            assertArrayEquals(before, after);
        } finally {
            free(result);
            owner.release();
        }
    }

    private static ByteBuf source(byte[] data, int storage) {
        if (storage == 0) {
            return ByteBuf.wrap(data);
        }
        ByteBuffer buffer = storage == 1 ? ByteBuffer.allocate(data.length) : ByteBuffer.allocateDirect(data.length);
        buffer.put(data);
        buffer.flip();
        return ByteBuf.wrap(buffer);
    }

    private static byte[] payload(int length) {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (i * 73 + (i >>> 8) * 19 + 0x91);
        }
        return payload;
    }

    private static byte[] content(WebSocketFrame frame) {
        byte[] data = new byte[frame.content().readableBytes()];
        frame.content().getBytes(0, data, 0, data.length);
        return data;
    }
}
