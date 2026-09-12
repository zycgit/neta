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
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ArrayPrimitiveReadTest {
    @Test
    public void intReadsRespectOrderOffsetsAndLiveArrayContents() {
        byte[] bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 73);
        }
        ByteBuf wrapped = ByteBuf.wrap(bytes);
        ByteBuf slice = wrapped.slice(3, 38);
        ByteBuf nested = slice.slice(2, 31);
        try {
            for (ByteOrder order : new ByteOrder[] { ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN }) {
                for (int index = 0; index < 3; index++) {
                    ByteBuf buffer = new ByteBuf[] { wrapped, slice, nested }[index];
                    int base = new int[] { 0, 3, 5 }[index];
                    buffer.order(order);
                    int reader = buffer.readerIndex();
                    for (int offset = 0; offset <= buffer.readableBytes() - 4; offset++) {
                        assertEquals(ByteBuffer.wrap(bytes).order(order).getInt(base + reader + offset), buffer.getInt32(offset));
                    }
                    assertEquals(reader, buffer.readerIndex());
                    bytes[base + reader + 1] ^= (byte) 0x81;
                    assertEquals(ByteBuffer.wrap(bytes).order(order).getInt(base + reader + 1), buffer.getInt32(1));
                    buffer.skipReadableBytes(1);
                }
            }
        } finally {
            nested.release();
            slice.release();
            wrapped.release();
        }
    }

    @Test
    public void intReadsKeepLogicalBoundsAndOwnership() {
        ByteBuf wrapped = ByteBuf.wrap(new byte[20]);
        ByteBuf slice = wrapped.slice(3, 9);
        wrapped.release();
        assertTrue(wrapped.isFree());
        try {
            assertEquals(0, slice.getInt32(5));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.getInt32(6));
            assertThrows(IllegalArgumentException.class, () -> slice.getInt32(-1));
            slice.skipReadableBytes(2);
            assertEquals(0, slice.getInt32(3));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.getInt32(4));
            slice.discardReadBytes();
            assertEquals(0, slice.getInt32(3));
        } finally {
            slice.release();
        }
        assertThrows(IllegalStateException.class, () -> slice.getInt32(0));
        assertThrows(IllegalStateException.class, () -> wrapped.getInt32(0));
    }

    @Test
    public void sourceBackedSliceKeepsPrimitiveFallback() {
        ByteBuf source = ByteBufAllocator.DEFAULT.directBuffer(32);
        source.writeBytes(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 });
        source.markWriter();
        ByteBuf slice = source.slice(1, 7);
        assertEquals(2, source.refCnt());
        try {
            assertEquals(0x02030405, slice.getInt32(1));
            slice.order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x05040302, slice.getInt32(1));
            assertThrows(IndexOutOfBoundsException.class, () -> slice.getInt32(4));
        } finally {
            slice.release();
            source.release();
        }
    }

    @Test
    public void wrappedIntReadsDoNotExposeUnmarkedWrites() {
        ByteBuf source = ByteBuf.wrap(new byte[12], true);
        try {
            source.writeBytes(new byte[] { 1, 2, 3, 4 });
            assertThrows(IndexOutOfBoundsException.class, () -> source.getInt32(0));
            source.markWriter();
            assertEquals(0x01020304, source.getInt32(0));
            assertThrows(IndexOutOfBoundsException.class, () -> source.getInt32(1));
        } finally {
            source.release();
        }
    }
}
