package net.hasor.neta.bytebuf;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import org.junit.Test;

/**
 * Tests for 3 additional issues found in the comprehensive audit:
 * ISSUE-A (P1): copy() does not preserve byteOrder — all 8 implementations
 * ISSUE-B (P2): ReadOnlyByteBuf allows order(ByteOrder) to modify underlying state
 * ISSUE-C (P3): RingByteBuffer._free() skips SmallBufferCache (style consistency)
 */
public class AuditFixVerificationTest {

    // ========================================================================
    // ISSUE-A: copy() must preserve byteOrder
    // When a buffer has LITTLE_ENDIAN order, its copy must also be LITTLE_ENDIAN.
    // ========================================================================

    @Test
    public void copy_preserves_littleEndian_heap() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304 : "copy should read same value with same byte order";
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_direct() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304 : "copy should read same value with same byte order";
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_pooled() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_wrapArray() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], true);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_wrapByteBuffer() {
        ByteBuf buf = ByteBuf.wrap(java.nio.ByteBuffer.allocate(16), true);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_ringHeap() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringHeapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_ringDirect() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringDirectBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "copy should preserve LITTLE_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_preserves_littleEndian_composite() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf part = ByteBufAllocator.DEFAULT.heapBuffer(16);
        part.order(ByteOrder.LITTLE_ENDIAN);
        part.writeInt32(0x01020304);
        part.markWriter();
        composite.addComponent(part);
        composite.order(ByteOrder.LITTLE_ENDIAN);

        ByteBuf copy = composite.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN : "composite copy should preserve LITTLE_ENDIAN, got " + copy.order();
        } finally {
            copy.free();
            composite.free();
            part.free();
        }
    }

    @Test
    public void copy_preserves_bigEndian_default() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        // default is BIG_ENDIAN, don't change it
        buf.writeInt32(0x01020304);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.BIG_ENDIAN : "copy should default to BIG_ENDIAN, got " + copy.order();
            assert copy.readInt32() == 0x01020304;
        } finally {
            copy.free();
            buf.free();
        }
    }

    @Test
    public void copy_littleEndian_data_integrity() {
        // Write in LITTLE_ENDIAN, copy, read back — values must match
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt16((short) 0x0102);
        buf.writeInt32(0x03040506);
        buf.writeInt64(0x0708091011121314L);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        try {
            assert copy.order() == ByteOrder.LITTLE_ENDIAN;
            assert copy.readInt16() == (short) 0x0102;
            assert copy.readInt32() == 0x03040506;
            assert copy.readInt64() == 0x0708091011121314L;
        } finally {
            copy.free();
            buf.free();
        }
    }

    // ========================================================================
    // ISSUE-B: ReadOnlyByteBuf must block order(ByteOrder)
    // Calling order(ByteOrder) on a read-only view was modifying the
    // underlying buffer's byte order through ByteBufProxy delegation.
    // ========================================================================

    @Test(expected = ReadOnlyBufferException.class)
    public void readOnly_order_withArg_throws() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        ByteBuf ro = buf.asReadOnly();
        ro.order(ByteOrder.LITTLE_ENDIAN); // should throw
    }

    @Test
    public void readOnly_order_noArg_allowed() {
        // Reading current byte order should work
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuf ro = buf.asReadOnly();
        assert ro.order() == ByteOrder.LITTLE_ENDIAN : "read-only should report underlying order";
        ro.free();
    }

    @Test
    public void readOnly_order_doesNotModifyUnderlying() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        assert buf.order() == ByteOrder.BIG_ENDIAN;
        ByteBuf ro = buf.asReadOnly();

        try {
            ro.order(ByteOrder.LITTLE_ENDIAN);
            assert false : "should throw ReadOnlyBufferException";
        } catch (ReadOnlyBufferException e) {
            // expected
        }

        // Verify underlying buffer was NOT modified
        assert buf.order() == ByteOrder.BIG_ENDIAN : "underlying buffer order should not be modified";
        ro.free();
    }

    // ========================================================================
    // ISSUE-C: RingByteBuffer._free() SmallBufferCache consistency
    // This is a style/consistency fix — hard to test directly, but we verify
    // that ring direct buffer free/allocate cycles work correctly.
    // ========================================================================

    @Test
    public void ringDirect_free_and_reallocate() {
        // Verify that ring direct buffer can be freed and reallocated
        // multiple times without issues (exercises the _free path)
        for (int i = 0; i < 10; i++) {
            ByteBuf buf = ByteBufAllocator.DEFAULT.ringDirectBuffer(64);
            buf.writeBytes(new byte[] { 1, 2, 3, 4 });
            buf.markWriter();
            assert buf.readByte() == 1;
            buf.free();
            assert buf.isFree();
        }
    }

    @Test
    public void ringDirect_small_buffer_free() {
        // Small ring direct buffer — should try SmallBufferCache path
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringDirectBuffer(32);
        buf.writeBytes(new byte[] { (byte) 0xAA, (byte) 0xBB });
        buf.markWriter();
        assert buf.readByte() == (byte) 0xAA;
        buf.free();
        assert buf.isFree();
    }
}
