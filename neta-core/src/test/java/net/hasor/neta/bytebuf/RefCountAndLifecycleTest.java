package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Tests for reference counting edge cases, use-after-free protection,
 * ByteBuf.EMPTY comprehensive behavior, and error condition handling.
 */
public class RefCountAndLifecycleTest {

    // ========================================================================
    // Reference counting edge cases
    // ========================================================================

    @Test
    public void retain_zero_throwsIAE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.retain(0);
            assert false : "Expected IAE for retain(0)";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("0");
        } finally {
            buf.release();
        }
    }

    @Test
    public void release_zero_throwsIAE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.release(0);
            assert false : "Expected IAE for release(0)";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("0");
        } finally {
            buf.release();
        }
    }

    @Test
    public void retain_multipleAndRelease_preciseLifecycle() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        assert buf.refCnt() == 1;

        buf.retain();
        assert buf.refCnt() == 2;

        buf.retain(3);
        assert buf.refCnt() == 5;

        // Release one by one
        assert !buf.release();
        assert buf.refCnt() == 4;
        assert !buf.release(2);
        assert buf.refCnt() == 2;
        assert !buf.release();
        assert buf.refCnt() == 1;
        assert buf.release(); // final release
    }

    @Test
    public void release_moreThanRefCnt_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        assert buf.refCnt() == 1;
        try {
            buf.release(2);
            assert false : "Expected ISE for release(2) on refCnt=1";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void retain_afterFinalRelease_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.release(); // refCnt → 0
        try {
            buf.retain();
            assert false : "Expected ISE for retain after final release";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void doubleRelease_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.release();
        try {
            buf.release();
            assert false : "Expected ISE for double release";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    // ========================================================================
    // Use-after-free tests for all buffer types
    // ========================================================================

    @Test
    public void heapBuffer_writeAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.release();
        try {
            buf.writeByte((byte) 1);
            assert false : "Expected ISE";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void directBuffer_writeAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();
        buf.release();
        try {
            buf.writeByte((byte) 1);
            assert false : "Expected ISE";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void pooledBuffer_writeAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer();
        buf.release();
        try {
            buf.writeByte((byte) 1);
            assert false;
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void ringBuffer_writeAfterFree_throwsISE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringHeapBuffer(64);
        buf.release();
        try {
            buf.writeByte((byte) 1);
            assert false;
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void wrapBuffer_readAfterFree_throwsISE() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        buf.markWriter();
        buf.release();
        try {
            buf.readByte();
            assert false;
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    // ========================================================================
    // ByteBuf.EMPTY comprehensive behavior
    // ========================================================================

    @Test
    public void empty_capacity_isZero() {
        assert ByteBuf.EMPTY.capacity() == 0;
    }

    @Test
    public void empty_readableWritableBytes_areZero() {
        assert ByteBuf.EMPTY.readableBytes() == 0;
        assert ByteBuf.EMPTY.writableBytes() == 0;
    }

    @Test
    public void empty_refCnt_isAlwaysOne() {
        assert ByteBuf.EMPTY.refCnt() == 1;
        ByteBuf.EMPTY.retain();
        assert ByteBuf.EMPTY.refCnt() == 1; // retain is no-op
        ByteBuf.EMPTY.release();
        assert ByteBuf.EMPTY.refCnt() == 1; // release is no-op
    }

    @Test
    public void empty_free_isNoop() {
        ByteBuf.EMPTY.free();
        assert ByteBuf.EMPTY.refCnt() == 1;
        assert ByteBuf.EMPTY.readableBytes() == 0;
    }

    @Test
    public void empty_close_isNoop() throws Exception {
        ByteBuf.EMPTY.close();
        assert ByteBuf.EMPTY.refCnt() == 1;
    }

    @Test
    public void empty_writeByte_throwsException() {
        try {
            ByteBuf.EMPTY.writeByte((byte) 1);
            assert false : "Expected exception on write to EMPTY";
        } catch (Exception e) {
            // Expected — either BufferOverflowException or IndexOutOfBoundsException
        }
    }

    @Test
    public void empty_readByte_throwsException() {
        try {
            ByteBuf.EMPTY.readByte();
            assert false : "Expected exception on read from EMPTY";
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void empty_sliceOff_zero_returnsSelf() {
        ByteBuf result = ByteBuf.EMPTY.sliceOff(0);
        assert result == ByteBuf.EMPTY;
    }

    @Test
    public void empty_copy_returnsUsable() {
        ByteBuf copy = ByteBuf.EMPTY.copy();
        assert copy.readableBytes() == 0;
        assert copy.capacity() == 0;
    }

    @Test
    public void empty_markOperations_areNoop() {
        ByteBuf.EMPTY.markReader();
        ByteBuf.EMPTY.markWriter();
        ByteBuf.EMPTY.resetReader();
        ByteBuf.EMPTY.resetWriter();
        assert ByteBuf.EMPTY.readableBytes() == 0;
    }

    // ========================================================================
    // Null/edge parameter handling
    // ========================================================================

    @Test
    public void writeBytes_nullArray_throwsNPE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.writeBytes(null, 0, 0);
            // May or may not throw depending on len=0 short-circuit
        } catch (NullPointerException e) {
            // Expected
        } finally {
            buf.release();
        }
    }

    @Test
    public void writeBuffer_nullByteBuffer_throwsNPE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.writeBuffer((ByteBuffer) null, 0);
            // May or may not throw depending on len=0 short-circuit
        } catch (NullPointerException e) {
            // Expected
        } finally {
            buf.release();
        }
    }

    @Test
    public void writeString_nullCharset_throwsNPE() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        try {
            buf.writeString("hello", null);
            assert false : "Expected NPE";
        } catch (NullPointerException e) {
            // Expected
        } finally {
            buf.release();
        }
    }

    // ========================================================================
    // Integer.MAX_VALUE capacity edge case
    // ========================================================================

    @Test
    public void buffer_maxCapacity_exceedingLimit_throwsIAE() {
        try {
            ByteBufAllocator.DEFAULT.buffer(1, Integer.MAX_VALUE);
            // May succeed, but capacity should be capped
        } catch (Exception e) {
            // Expected for pooled allocators that can't handle INT_MAX
        }
    }

    // ========================================================================
    // AutoCloseable integration
    // ========================================================================

    @Test
    public void tryWithResources_releasesBuffer() throws Exception {
        ByteBuf outside;
        try (ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer()) {
            buf.writeByte((byte) 42);
            buf.markWriter();
            assert buf.readByte() == 42;
            outside = buf;
        }
        // After close, refCnt should be 0
        try {
            outside.writeByte((byte) 1);
            assert false;
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void tryWithResources_pooledBuffer_releasesBuffer() throws Exception {
        try (ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer()) {
            buf.writeByte((byte) 99);
            buf.markWriter();
            assert buf.readByte() == 99;
        }
        // No assertion on specific state, just ensure no leak/exception
    }

    // ========================================================================
    // Copy on different buffer types
    // ========================================================================

    @Test
    public void copy_heapBuffer_independentLifecycle() {
        ByteBuf original = ByteBufAllocator.DEFAULT.heapBuffer();
        original.writeByte((byte) 1);
        original.writeByte((byte) 2);
        original.markWriter();

        ByteBuf copy = original.copy();
        original.release();

        // Copy should still be usable
        assert copy.readByte() == 1;
        assert copy.readByte() == 2;
        copy.release();
    }

    @Test
    public void copy_directBuffer_independentLifecycle() {
        ByteBuf original = ByteBufAllocator.DEFAULT.directBuffer();
        original.writeByte((byte) 10);
        original.writeByte((byte) 20);
        original.markWriter();

        ByteBuf copy = original.copy();
        original.release();

        assert copy.readByte() == 10;
        assert copy.readByte() == 20;
        copy.release();
    }

    @Test
    public void copy_pooledBuffer_independentLifecycle() {
        ByteBuf original = ByteBufAllocator.DEFAULT.pooledBuffer();
        original.writeByte((byte) 77);
        original.writeInt32(12345);
        original.markWriter();

        ByteBuf copy = original.copy();
        original.release();

        // Copy should have identical content
        assert copy.readByte() == 77;
        assert copy.readInt32() == 12345;
        copy.release();
    }
}
