/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Edge case tests for ByteBuf implementations:
 * - 0-byte operations
 * - capacity boundary writes
 * - use-after-free detection
 * - negative/invalid parameters
 * - skip operations
 * - mark/reset behavior
 * - asByteArray
 * - discardReadBytes
 * - sliceOff edge cases
 */
public class ByteBufEdgeCaseTest {

    // ========================================================================
    // 0-byte operations
    // ========================================================================

    @Test
    public void writeZeroBytes_isNoop() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        int written = buf.writeBytes(new byte[0]);
        assert written == 0;
        assert buf.writerIndex() == 0;
        buf.free();
    }

    @Test
    public void readZeroBytes_isNoop() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter();

        int read = buf.readBytes(new byte[0]);
        assert read == 0;
        assert buf.readerIndex() == 0;
        buf.free();
    }

    @Test
    public void writeBuffer_zeroLength_isNoop() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBuffer src = ByteBuffer.allocate(0);

        int written = buf.writeBuffer(src, 0);
        assert written == 0;
        buf.free();
    }

    @Test
    public void sliceOff_zero_returnsEmpty_heapArray() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBuf sliced = buf.sliceOff(0);
        assert sliced == ByteBuf.EMPTY;
        buf.free();
    }

    @Test
    public void sliceOff_zero_returnsEmpty_wrap() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf sliced = buf.sliceOff(0);
        assert sliced == ByteBuf.EMPTY;
        buf.free();
    }

    @Test
    public void sliceOff_zero_returnsEmpty_wrapByteBuffer() {
        ByteBuf buf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
        ByteBuf sliced = buf.sliceOff(0);
        assert sliced == ByteBuf.EMPTY;
        buf.free();
    }

    // ========================================================================
    // use-after-free detection
    // ========================================================================

    @Test
    public void readAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2 });
        buf.markWriter();
        buf.free();

        try {
            buf.readByte();
            assert false : "should throw";
        } catch (IllegalStateException e) {
            // expected: "has been released."
        }
    }

    @Test
    public void writeAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.free();

        try {
            buf.writeByte((byte) 1);
            assert false : "should throw";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void isFree_afterRelease() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        assert !buf.isFree();
        assert buf.refCnt() == 1;

        buf.free();
        assert buf.isFree();
        assert buf.refCnt() == 0;
    }

    @Test
    public void doubleRelease_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.free();

        try {
            buf.free();
            assert false : "double release should throw";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    // ========================================================================
    // Capacity boundary tests
    // ========================================================================

    @Test
    public void writeExactCapacity_succeeds() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(4, 4);
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        assert buf.writerIndex() == 4;
        buf.free();
    }

    @Test
    public void writeExceedingCapacity_overflow() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(4, 4);
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });

        try {
            buf.writeByte((byte) 5);
            assert false : "should throw BufferOverflowException";
        } catch (BufferOverflowException e) {
            // expected
        }
        buf.free();
    }

    @Test
    public void readBeyondWriteMark_throwsIOOBE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        // not marking writer -> markedWriterIndex = 0, readableBytes = 0

        try {
            buf.readByte();
            assert false : "should throw IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected: need markWriter before reading
        }
        buf.free();
    }

    // ========================================================================
    // Skip operations
    // ========================================================================

    @Test
    public void skipReadableBytes_advancesReader() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        buf.skipReadableBytes(3);
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        buf.free();
    }

    @Test
    public void skipWritableBytes_advancesWriter() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        buf.skipWritableBytes(5);
        assert buf.writerIndex() == 5;
        buf.free();
    }

    @Test
    public void skipReadableBytes_exceedingReadable_throwsIOOBE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2 });
        buf.markWriter();

        try {
            buf.skipReadableBytes(3); // only 2 readable
            assert false : "should throw";
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        buf.free();
    }

    // ========================================================================
    // mark / reset tests
    // ========================================================================

    @Test
    public void markWriter_thenResetWriter() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter(); // markedWriterIndex = 3

        buf.writeBytes(new byte[] { 4, 5, 6 });
        assert buf.writerIndex() == 6;

        buf.resetWriter();
        assert buf.writerIndex() == 3;

        // new writes overwrite from position 3
        buf.writeBytes(new byte[] { 7, 8 });
        buf.markWriter();

        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        assert buf.readByte() == 3;
        assert buf.readByte() == 7;
        assert buf.readByte() == 8;
        buf.free();
    }

    @Test
    public void markReader_thenResetReader() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        buf.readByte(); // 1
        buf.readByte(); // 2
        buf.markReader(); // marked at 2

        buf.readByte(); // 3
        buf.readByte(); // 4

        buf.resetReader();
        // should be back to markedReaderIndex (2)
        assert buf.readByte() == 3;
        buf.free();
    }

    // ========================================================================
    // asByteArray tests
    // ========================================================================

    @Test
    public void asByteArray_returnsFullBufferContent() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 10, 20, 30, 40, 50 });
        buf.markWriter();

        buf.readByte(); // advance reader to 1
        buf.markReader();

        byte[] arr = buf.asByteArray();
        // asByteArray returns from markedReaderIndex to markedWriterIndex
        assert arr.length == 4 : "expected length 4, got " + arr.length;
        assert arr[0] == 20;
        assert arr[1] == 30;
        assert arr[2] == 40;
        assert arr[3] == 50;
        buf.free();
    }

    @Test
    public void asByteArray_afterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1 });
        buf.markWriter();
        buf.free();

        try {
            buf.asByteArray();
            assert false : "should throw";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    // ========================================================================
    // discardReadBytes edge cases
    // ========================================================================

    @Test
    public void discardReadBytes_noReaderAdvance_isNoop() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        buf.markWriter();

        // readerIndex is 0, discardReadBytes should be a no-op
        buf.discardReadBytes();
        assert buf.readerIndex() == 0;
        assert buf.readByte() == 1;
        buf.free();
    }

    @Test
    public void discardReadBytes_afterPartialRead() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        buf.readByte(); // 1
        buf.readByte(); // 2

        buf.discardReadBytes();

        // After discard: data shifted, readerIndex reset to 0
        assert buf.readerIndex() == 0;
        assert buf.readByte() == 3;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        buf.free();
    }

    @Test
    public void discardReadBytes_whenAllRead() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter();

        buf.readByte(); // 1
        buf.readByte(); // 2
        buf.readByte(); // 3

        // readerIndex == writerIndex
        buf.discardReadBytes();
        assert buf.readerIndex() == 0;
        assert buf.writerIndex() == 0;
        buf.free();
    }

    // ========================================================================
    // WrapArrayBuffer specific edge cases
    // ========================================================================

    @Test
    public void wrapArrayBuffer_basicOperations() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30, 40, 50 });
        assert buf.capacity() == 5;
        assert !buf.isDirect();
        assert buf.readableBytes() == 5;

        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        assert buf.readByte() == 40;
        assert buf.readByte() == 50;
        buf.free();
    }

    @Test
    public void wrapArrayBuffer_asWrite() {
        ByteBuf buf = ByteBuf.wrap(new byte[10], true);
        assert buf.capacity() == 10;
        assert buf.writableBytes() == 10;
        assert buf.readableBytes() == 0;

        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter();

        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        assert buf.readByte() == 3;
        buf.free();
    }

    @Test
    public void wrapByteBuffer_basicOperations() {
        ByteBuf buf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 10, 20, 30 }));
        assert buf.capacity() == 3;
        assert buf.readableBytes() == 3;

        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        buf.free();
    }

    @Test
    public void wrapByteBuffer_direct() {
        ByteBuffer directBuf = ByteBuffer.allocateDirect(8);
        directBuf.put(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        directBuf.flip();

        ByteBuf buf = ByteBuf.wrap(directBuf);
        assert buf.isDirect();
        assert buf.capacity() == 8;
        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        buf.free();
    }

    // ========================================================================
    // WrapByteBuffer sliceOff preserves direct/heap
    // ========================================================================

    @Test
    public void wrapByteBuffer_sliceOff_heapReturnsHeap() {
        ByteBuf buf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 }));
        ByteBuf sliced = buf.sliceOff(3);
        assert !sliced.isDirect() : "sliceOff from heap should return heap";
        assert sliced.readByte() == 1;
        assert sliced.readByte() == 2;
        assert sliced.readByte() == 3;
        sliced.free();
        buf.free();
    }

    @Test
    public void wrapByteBuffer_sliceOff_directReturnsDirect() {
        ByteBuffer directBuf = ByteBuffer.allocateDirect(5);
        directBuf.put(new byte[] { 10, 20, 30, 40, 50 });
        directBuf.flip();

        ByteBuf buf = ByteBuf.wrap(directBuf);
        ByteBuf sliced = buf.sliceOff(3);
        assert sliced.isDirect() : "sliceOff from direct should return direct";
        assert sliced.readByte() == 10;
        assert sliced.readByte() == 20;
        assert sliced.readByte() == 30;
        sliced.free();
        buf.free();
    }

    // ========================================================================
    // sliceOff negative / out-of-range
    // ========================================================================

    @Test
    public void sliceOff_negativeOffset_throwsIOOBE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        try {
            buf.sliceOff(-1);
            assert false : "should throw";
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        buf.free();
    }

    @Test
    public void sliceOff_exceedCapacity_throwsIOOBE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(8, 8);
        try {
            buf.sliceOff(9);
            assert false : "should throw";
        } catch (IndexOutOfBoundsException e) {
            // expected
        }
        buf.free();
    }

    // ========================================================================
    // ByteBuf.EMPTY behavior
    // ========================================================================

    @Test
    public void emptyByteBuf_isSpecial() {
        ByteBuf empty = ByteBuf.EMPTY;
        assert empty.capacity() == 0;
        assert empty.readableBytes() == 0;
        assert empty.writableBytes() == 0;

        // retain/release should be no-op
        assert empty.retain() == empty;
        assert empty.retain(5) == empty;
        assert !empty.release();
        assert !empty.release(5);

        // free and close should be no-op
        empty.free();
        try {
            empty.close();
        } catch (Exception e) {
            assert false : "EMPTY close should not throw";
        }
    }

    // ========================================================================
    // ByteBuf toString format
    // ========================================================================

    @Test
    public void toString_containsExpectedFormat() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter();
        buf.readByte();

        String str = buf.toString();
        assert str.contains("rMark=") : "toString should contain rMark=";
        assert str.contains("rIndex=") : "toString should contain rIndex=";
        assert str.contains("wMark=") : "toString should contain wMark=";
        assert str.contains("wIndex=") : "toString should contain wIndex=";
        assert str.contains("capacity=") : "toString should contain capacity=";
        buf.free();
    }

    // ========================================================================
    // get/set with ByteBuffer and ByteBuf sources
    // ========================================================================

    @Test
    public void setBuffer_and_getBuffer_withByteBuffer() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        buf.writeBytes(new byte[16]);

        ByteBuffer src = ByteBuffer.wrap(new byte[] { 11, 22, 33, 44 });
        buf.setBuffer(0, src, 4);
        buf.markWriter();

        ByteBuffer dst = ByteBuffer.allocate(4);
        buf.getBuffer(0, dst, 4);
        dst.flip();

        assert dst.get() == 11;
        assert dst.get() == 22;
        assert dst.get() == 33;
        assert dst.get() == 44;
        buf.free();
    }

    @Test
    public void setBuffer_and_getBuffer_withByteBuf() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        buf.writeBytes(new byte[16]);

        ByteBuf src = ByteBuf.wrap(new byte[] { 55, 66, 77, 88 });
        buf.setBuffer(0, src, 4);
        buf.markWriter();

        ByteBuf dst = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.getBuffer(0, dst, 4);
        dst.markWriter();

        assert dst.readByte() == 55;
        assert dst.readByte() == 66;
        assert dst.readByte() == 77;
        assert dst.readByte() == 88;

        src.free();
        dst.free();
        buf.free();
    }

    // ========================================================================
    // readBytes / writeBytes with offset and length
    // ========================================================================

    @Test
    public void writeBytes_withOffset() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        byte[] data = { 10, 20, 30, 40, 50 };
        buf.writeBytes(data, 2, 3); // write [30, 40, 50]
        buf.markWriter();

        assert buf.readByte() == 30;
        assert buf.readByte() == 40;
        assert buf.readByte() == 50;
        buf.free();
    }

    @Test
    public void readBytes_withOffset() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        byte[] dst = new byte[7];
        int read = buf.readBytes(dst, 2, 3); // read 3 bytes into dst starting at offset 2
        assert read == 3;
        assert dst[0] == 0; // untouched
        assert dst[1] == 0;
        assert dst[2] == 1;
        assert dst[3] == 2;
        assert dst[4] == 3;
        buf.free();
    }

    // ========================================================================
    // AutoCloseable (try-with-resources)
    // ========================================================================

    @Test
    public void autoCloseable_freeOnClose() throws Exception {
        ByteBuf buf;
        try (ByteBuf b = ByteBufAllocator.DEFAULT.heapBuffer(16)) {
            b.writeBytes(new byte[] { 1, 2, 3 });
            b.markWriter();
            assert b.readByte() == 1;
            buf = b;
        }
        assert buf.isFree();
    }

    // ========================================================================
    // Direct buffer edge cases
    // ========================================================================

    @Test
    public void directBuffer_discardReadBytes() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(16, 16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        buf.readByte(); // 1
        buf.readByte(); // 2
        buf.discardReadBytes();

        assert buf.readerIndex() == 0;
        assert buf.readByte() == 3;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        buf.free();
    }

    @Test
    public void directBuffer_copy() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(16);
        buf.writeBytes(new byte[] { 10, 20, 30 });
        buf.markWriter();

        ByteBuf copy = buf.copy();
        assert copy.isDirect();
        assert copy.readByte() == 10;
        assert copy.readByte() == 20;
        assert copy.readByte() == 30;

        // modifying copy shouldn't affect original
        buf.readByte(); // advance original reader
        assert buf.readByte() == 20;

        copy.free();
        buf.free();
    }
}
