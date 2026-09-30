/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class ByteBufLongReadTest {
    @Test
    public void longReadsRespectOrderOffsetsSlicesAndReadOnlyViews() {
        byte[] data = new byte[81];
        new Random(88).nextBytes(data);
        for (int storage = 0; storage < 4; storage++) {
            for (ByteOrder order : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                ByteBuf source = source(data, storage);
                try {
                    source.skipReadableBytes(3);
                    ByteBuf slice = source.slice(5, 60);
                    try {
                        slice.skipReadableBytes(2);
                        source.order(order);
                        slice.order(order);
                        verify(source, data, 3, order);
                        verify(slice, data, 10, order);
                        ByteBuf readOnly = slice.retain().asReadOnly();
                        try {
                            verify(readOnly, data, 10, order);
                        } finally {
                            readOnly.free();
                        }
                    } finally {
                        slice.free();
                    }
                } finally {
                    source.free();
                }
            }
        }
    }

    @Test
    public void invalidOrReleasedReadsFailWithoutMovingIndices() {
        for (int storage = 0; storage < 4; storage++) {
            ByteBuf source = source(new byte[32], storage);
            source.skipReadableBytes(5);
            ByteBuf slice = source.slice(3, 17);
            try {
                for (ByteBuf buffer : new ByteBuf[] { source, slice }) {
                    int reader = buffer.readerIndex();
                    for (int offset : new int[] { -1, buffer.readableBytes() - 7, buffer.readableBytes(), Integer.MAX_VALUE }) {
                        try {
                            buffer.getInt64(offset);
                            fail("Accepted invalid offset " + offset);
                        } catch (IndexOutOfBoundsException | IllegalArgumentException expected) {
                            assertEquals(reader, buffer.readerIndex());
                        }
                    }
                }
            } finally {
                slice.free();
                source.free();
            }
            try {
                source.getInt64(0);
                fail("Accepted released source");
            } catch (IllegalStateException expected) {
                assertTrue(source.isFree());
            }
        }
    }

    private static void verify(ByteBuf buffer, byte[] data, int base, ByteOrder order) {
        assertEquals(order, buffer.order());
        int reader = buffer.readerIndex();
        int writer = buffer.writerIndex();
        ByteBuffer expected = ByteBuffer.wrap(data).order(order);
        for (int offset = 0; offset <= buffer.readableBytes() - Long.BYTES; offset++) {
            assertEquals(expected.getLong(base + offset), buffer.getInt64(offset));
        }
        assertEquals(reader, buffer.readerIndex());
        assertEquals(writer, buffer.writerIndex());
    }

    private static ByteBuf source(byte[] data, int storage) {
        if (storage == 0) {
            return ByteBuf.wrap(data);
        }
        if (storage == 1) {
            ByteBuf buffer = ByteBufAllocator.DEFAULT.heapBuffer(data.length);
            buffer.writeBytes(data);
            buffer.markWriter();
            return buffer;
        }
        ByteBuffer buffer = storage == 2 ? ByteBuffer.allocate(data.length) : ByteBuffer.allocateDirect(data.length);
        buffer.put(data).flip();
        return ByteBuf.wrap(buffer);
    }
}
