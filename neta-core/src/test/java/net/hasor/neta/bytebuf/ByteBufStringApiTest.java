/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ByteBufStringApiTest {
    @Test
    public void stringsMatchExistingDecodingAcrossRangesAndBuffers() {
        byte[] data = new byte[260];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        Charset[] charsets = { StandardCharsets.US_ASCII, StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_16, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE, null };
        for (int mode = 0; mode < 7; mode++) {
            ByteBuf buffer = input(data, mode);
            try {
                buffer.skipReadableBytes(3);
                int reader = buffer.readerIndex();
                int writer = buffer.writerIndex();
                for (Charset charset : charsets) {
                    for (int offset : new int[] { -1, 0, 1, 125, 255, 257, Integer.MAX_VALUE }) {
                        for (int length : new int[] { -1, 0, 1, 2, 7, 64, 256 }) {
                            assertEquals("mode=" + mode + ", offset=" + offset + ", length=" + length + ", charset=" + charset, outcome(buffer, offset, length, charset, true), outcome(buffer, offset, length, charset, false));
                            assertEquals(reader, buffer.readerIndex());
                            assertEquals(writer, buffer.writerIndex());
                        }
                    }
                }
            } finally {
                buffer.release();
            }
            assertEquals("", buffer.getString(-1, 0, null));
            assertEquals(outcome(buffer, 0, 1, StandardCharsets.UTF_8, true), outcome(buffer, 0, 1, StandardCharsets.UTF_8, false));
        }
    }

    @Test
    public void stringsSurviveSourceMutationAndRelease() {
        byte[] data = "prefix-stable-value".getBytes(StandardCharsets.UTF_8);
        for (int mode = 0; mode < 7; mode++) {
            ByteBuf buffer = input(data, mode);
            String text;
            try {
                text = buffer.getString(7, 6, StandardCharsets.UTF_8);
            } finally {
                buffer.release();
            }
            assertEquals("stable", text);
        }
        byte[] shared = data.clone();
        ByteBuf buffer = ByteBuf.wrap(shared);
        String text = buffer.getString(7, 6, StandardCharsets.UTF_8);
        Arrays.fill(shared, (byte) '!');
        buffer.release();
        assertEquals("stable", text);
    }

    @Test
    public void customCharsetCannotMutateBackingStorage() {
        Charset charset = new Charset("X-buffer-isolation", new String[0]) {
            @Override
            public boolean contains(Charset other) {
                return false;
            }

            @Override
            public CharsetDecoder newDecoder() {
                return new CharsetDecoder(this, 1, 1) {
                    @Override
                    protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
                        while (in.hasRemaining()) {
                            if (!out.hasRemaining()) {
                                return CoderResult.OVERFLOW;
                            }
                            out.put((char) (in.get() & 0xff));
                        }
                        if (in.hasArray()) {
                            Arrays.fill(in.array(), (byte) '!');
                        }
                        return CoderResult.UNDERFLOW;
                    }
                };
            }

            @Override
            public CharsetEncoder newEncoder() {
                throw new UnsupportedOperationException();
            }
        };
        byte[] data = "prefix-stable-suffix".getBytes(StandardCharsets.UTF_8);
        for (int mode = 0; mode < 7; mode++) {
            ByteBuf buffer = input(data, mode);
            try {
                assertEquals("stable", buffer.getString(7, 6, charset));
                assertEquals("prefix-stable-suffix", buffer.getString(0, data.length, StandardCharsets.UTF_8));
            } finally {
                buffer.release();
            }
        }
    }

    private static Object outcome(ByteBuf buffer, int offset, int length, Charset charset, boolean legacy) {
        try {
            if (!legacy) {
                return buffer.getString(offset, length, charset);
            }
            if (length == 0) {
                return "";
            }
            byte[] copy = new byte[length];
            int read = buffer.getBytes(offset, copy);
            return charset == StandardCharsets.US_ASCII ? new String(copy, 0, read) : new String(copy, 0, read, charset);
        } catch (RuntimeException e) {
            return e.getClass();
        }
    }

    private static ByteBuf input(byte[] data, int mode) {
        if (mode == 0) {
            return ByteBuf.wrap(data.clone());
        }
        if (mode == 1) {
            ByteBuf buffer = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.heapBuffer(1, data.length);
            buffer.writeBytes(data);
            buffer.markWriter();
            return buffer;
        }
        if (mode == 2 || mode == 3 || mode == 4) {
            byte[] padded = new byte[data.length + 4];
            System.arraycopy(data, 0, padded, 2, data.length);
            ByteBuf parent = ByteBuf.wrap(padded);
            if (mode == 2) {
                ByteBuf slice = parent.slice(2, data.length);
                parent.release();
                return slice;
            }
            ByteBuf slice = parent.slice(1, data.length + 2);
            parent.release();
            ByteBuf nested = slice.slice(1, data.length);
            slice.release();
            return mode == 4 ? nested.asReadOnly() : nested;
        }
        ByteBuf direct = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR.directBuffer(data.length, data.length);
        direct.writeBytes(data);
        direct.markWriter();
        if (mode == 5) {
            ByteBuf slice = direct.slice(0, data.length);
            direct.release();
            return slice;
        }
        return direct;
    }
}
