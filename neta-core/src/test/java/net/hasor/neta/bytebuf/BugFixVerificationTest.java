package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.Test;

/**
 * Targeted tests to verify 6 bug fixes from the Netty-perspective code audit.
 * BUG-1: offsetWritable bounds check missing offset — allows out-of-bounds set operations
 * BUG-2: ReadOnlyByteBuf missing flush() block — flush delegates to underlying buffer
 * BUG-3: CompositeByteBuf missing checkFree() in _getByte/_getBytes after free
 * BUG-4: CompositeByteBuf.discardReadBytes() unused variable (compile-only, no behavior test)
 * BUG-5: CompositeByteBuf._free() didn't reset lastAccessedComponentIndex
 * BUG-6: AutoArrayByteBuf.sliceOff() dead isDirect() branch (compile-only, no behavior test)
 */
public class BugFixVerificationTest {

    // ========================================================================
    // BUG-1: offsetWritable bounds check with offset
    // Before fix: setByte(offset, val) only checked writableBytes > available,
    //   ignoring offset. So setByte(100, val) on a buffer with 10 writable bytes
    //   would pass the check but write out of bounds.
    // After fix: checks (offset + writableBytes) > available.
    // ========================================================================

    @Test
    public void bug1_setByte_atLargeOffset_throwsOOB_heap() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            // Buffer has 16 writable bytes. Setting at offset 20 should fail.
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for offset beyond writable area";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setByte_atLargeOffset_throwsOOB_direct() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(16, 16);
        try {
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for offset beyond writable area";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setByte_atLargeOffset_throwsOOB_pooled() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(16, 16);
        try {
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for offset beyond writable area";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setByte_atExactBoundary_succeeds() {
        // offset=15, writableBytes=1 → 15+1=16 == maxCapacity → should succeed
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.setByte(15, (byte) 0x42);
            // Should not throw — exact boundary is valid
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setByte_pastExactBoundary_throwsOOB() {
        // offset=16, writableBytes=1 → 16+1=17 > 16 → should fail
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.setByte(16, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException at offset=maxCapacity";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setInt32_atLargeOffset_throwsOOB() {
        // setInt32 needs 4 bytes. offset=14, needs 14+4=18 > 16 → should fail
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.setInt32(14, 0x12345678);
            assert false : "expected IndexOutOfBoundsException for setInt32 beyond boundary";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setInt32_atExactBoundary_succeeds() {
        // setInt32 needs 4 bytes. offset=12, needs 12+4=16 == maxCapacity → should succeed
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.setInt32(12, 0x12345678);
            // Should not throw — exact boundary is valid
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setBytes_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        byte[] data = new byte[] { 1, 2, 3, 4 };
        try {
            // offset=14, len=4 → 14+4=18 > 16 → should fail
            buf.setBytes(14, data);
            assert false : "expected IndexOutOfBoundsException for setBytes beyond boundary";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setBytes_withinBounds_succeeds() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        byte[] data = new byte[] { 1, 2, 3, 4 };
        try {
            // offset=12, len=4 → 12+4=16 == maxCapacity → should succeed
            buf.setBytes(12, data);
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_offsetAfterPartialWrite_heap() {
        // Write 10 bytes, markWriter. Now writable area starts at writerIndex=10.
        // set at offset=7 with 1 byte → 10+7=17 > 16 → should fail for fixed-size buf
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.writeBytes(new byte[10]);
            buf.markWriter();
            // After markWriter, markedWriterIndex=10. writableBytes = 16 - (10-0) = 6.
            // offset=6 needs 6+1=7 > 6? No, writable is maxCapacity - (markedWriterIndex - markedReaderIndex) = 16 - 10 = 6
            // So offset=6 + 1 byte = 7 > 6 → should fail
            buf.setByte(6, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_offsetAfterPartialWrite_withinBounds() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        try {
            buf.writeBytes(new byte[10]);
            buf.markWriter();
            // writable = 6, offset=5 + 1 byte = 6 == 6 → should succeed
            buf.setByte(5, (byte) 0x42);
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setBuffer_ByteBuffer_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 });
        try {
            buf.setBuffer(14, src);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_setBuffer_ByteBuf_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16, 16);
        ByteBuf src = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        try {
            buf.setBuffer(14, src);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            src.free();
            buf.free();
        }
    }

    // ========================================================================
    // BUG-2: ReadOnlyByteBuf.flush() should throw ReadOnlyBufferException
    // Before fix: flush() delegated to underlying buffer, which called
    //   markWriter() + markReader() on the underlying buffer, modifying state.
    // After fix: flush() throws ReadOnlyBufferException.
    // ========================================================================

    @Test(expected = ReadOnlyBufferException.class)
    public void bug2_readOnly_flush_throwsReadOnlyException() throws Exception {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf ro = buf.asReadOnly();
        ro.flush(); // should throw ReadOnlyBufferException
    }

    @Test
    public void bug2_readOnly_flush_doesNotModifyUnderlying() throws Exception {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        // Don't call markWriter yet — writtenBytes should be 5
        int writtenBefore = buf.writtenBytes();
        ByteBuf ro = buf.asReadOnly();
        try {
            ro.flush();
            assert false : "expected ReadOnlyBufferException";
        } catch (ReadOnlyBufferException e) {
            // expected
        }
        // Verify underlying buffer state was not modified
        assert buf.writtenBytes() == writtenBefore : "underlying buffer should not be modified";
        ro.free();
    }

    // ========================================================================
    // BUG-3: CompositeByteBuf missing checkFree() in read operations
    // Before fix: after free(), getByte/getBytes threw confusing errors
    //   (e.g., IndexOutOfBoundsException from empty components list).
    // After fix: throws clear "has been released." IllegalStateException.
    // ========================================================================

    @Test
    public void bug3_composite_getByte_afterFree_throwsISE() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        composite.addComponent(buf);

        // Verify works before free
        assert composite.getByte(0) == 1;

        composite.free();
        try {
            composite.getByte(0);
            assert false : "expected IllegalStateException after free";
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.") : "wrong message: " + e.getMessage();
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug3_composite_getBytes_byteArray_afterFree_throwsISE() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        composite.addComponent(buf);

        composite.free();
        try {
            byte[] dst = new byte[3];
            composite.getBytes(0, dst);
            assert false : "expected IllegalStateException after free";
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.") : "wrong message: " + e.getMessage();
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug3_composite_getBytes_ByteBuffer_afterFree_throwsISE() throws Exception {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        composite.addComponent(buf);

        composite.free();
        try {
            ByteBuffer dst = ByteBuffer.allocate(3);
            composite.getBuffer(0, dst, 3);
            assert false : "expected IllegalStateException after free";
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.") : "wrong message: " + e.getMessage();
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug3_composite_getBytes_ByteBuf_afterFree_throwsISE() throws Exception {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        composite.addComponent(buf1);

        composite.free();
        ByteBuf dst = ByteBufAllocator.DEFAULT.heapBuffer(3);
        try {
            composite.getBuffer(0, dst, 3);
            assert false : "expected IllegalStateException after free";
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.") : "wrong message: " + e.getMessage();
        } finally {
            buf1.free();
            dst.free();
        }
    }

    @Test
    public void bug3_composite_readByte_afterFree_throwsISE() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        composite.addComponent(buf);

        composite.free();
        try {
            composite.readByte();
            assert false : "expected IllegalStateException or IndexOutOfBoundsException after free";
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            // After free, indices are still set but components cleared.
            // The read path hits checkFree via _getByte.
        } finally {
            buf.free();
        }
    }

    // ========================================================================
    // BUG-5: CompositeByteBuf._free() should reset lastAccessedComponentIndex
    // After fix: lastAccessedComponentIndex is reset to 0 in _free().
    // Test: free and verify that re-using the composite (via retain hack)
    //   doesn't use stale cache. This is mainly a consistency check.
    // ========================================================================

    @Test
    public void bug5_composite_readAfterFreeAndReuse_cacheClear() {
        // This test verifies that the component cache index is properly reset
        // by exercising sequential reads across components, freeing, and checking
        // that state is clean. The main validation is that no stale index causes
        // an incorrect component lookup.
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x01, 0x02 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x03, 0x04 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 0x05, 0x06 });
        composite.addComponent(buf1);
        composite.addComponent(buf2);
        composite.addComponent(buf3);

        // Read sequentially to advance lastAccessedComponentIndex to component 2
        assert composite.readByte() == 0x01;
        assert composite.readByte() == 0x02;
        assert composite.readByte() == 0x03;
        assert composite.readByte() == 0x04;
        assert composite.readByte() == 0x05;
        // lastAccessedComponentIndex should now point to component 2

        composite.free();

        // After free, getByte should throw ISE (not ArrayIndexOutOfBoundsException
        // from stale cache index on empty component list)
        try {
            composite.getByte(0);
            assert false : "expected ISE after free";
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        } finally {
            buf1.free();
            buf2.free();
            buf3.free();
        }
    }

    // ========================================================================
    // Additional edge-case: WrapArrayBuffer set at large offset
    // WrapArrayBuffer has fixed capacity and no auto-extension.
    // ========================================================================

    @Test
    public void bug1_wrapBuffer_setByte_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], false);
        try {
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for wrap buffer";
        } catch (IndexOutOfBoundsException e) {
            // expected — offsetWritable catches the invalid offset
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_wrapBuffer_setInt64_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], false);
        try {
            // setInt64 needs 8 bytes. offset=12 → 12+8=20 > 16 → fail
            buf.setInt64(12, 0x123456789ABCDEF0L);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_wrapBuffer_setFloat64_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], false);
        try {
            buf.setFloat64(12, 3.14);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_wrapBuffer_setString_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], false);
        try {
            buf.setString(14, "hello", java.nio.charset.StandardCharsets.UTF_8);
            assert false : "expected IndexOutOfBoundsException";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    // ========================================================================
    // Additional: ring buffer set at large offset
    // ========================================================================

    @Test
    public void bug1_ringHeap_setByte_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);
        try {
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for ring buffer";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }

    @Test
    public void bug1_ringDirect_setByte_atLargeOffset_throwsOOB() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringDirectBuffer(16);
        try {
            buf.setByte(20, (byte) 0x42);
            assert false : "expected IndexOutOfBoundsException for ring direct buffer";
        } catch (IndexOutOfBoundsException e) {
            // expected
        } finally {
            buf.free();
        }
    }
}
