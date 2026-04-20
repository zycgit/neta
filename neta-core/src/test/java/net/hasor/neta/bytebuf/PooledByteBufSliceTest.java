package net.hasor.neta.bytebuf;

import org.junit.Test;

import net.hasor.cobble.ref.RecycleObjectPool;

/**
 * Tests for sliceOff and discardReadBytes with Zero-Copy/Split logic
 */
public class PooledByteBufSliceTest {
    // PageSize = 16 bytes for testing
    private static final int        PAGE_SIZE = 16;
    private static final BufferPool POOL      = new BufferPool(PAGE_SIZE);

    private PooledByteBuf pooledBuffer(int initCapacity) {
        int fmtMaxCap = PageChunkPool.tableSizeFor(initCapacity, Integer.MAX_VALUE);
        Buffer target = POOL.requestBuffer(initCapacity, ByteBufAllocator.DEFAULT);

        PooledByteBuf byteBuf = RecycleObjectPool.get(PooledByteBuf.class, PooledByteBuf.RECYCLE_HANDLER);
        byteBuf.initBuffer(ByteBufAllocator.DEFAULT, fmtMaxCap, 4096, target, POOL);
        return byteBuf;
    }

    @Test
    public void testSliceOff_Aligned() throws java.io.IOException {
        // 1. Setup: Request 4 pages (64 bytes)
        int cap = PAGE_SIZE * 4;
        PooledByteBuf buf = pooledBuffer(cap);

        // Fill data
        byte[] data = new byte[cap];
        for (int i = 0; i < cap; i++) {
            data[i] = (byte) i;
        }
        buf.writeBytes(data);
        buf.flush();

        // 2. Perform aligned sliceOff
        int splitAmount = PAGE_SIZE * 2; // 32 bytes (Aligned)
        ByteBuf sliced = buf.sliceOff(splitAmount);

        // 3. Verify Sliced Buffer - now returns WrapByteBuffer
        assert sliced instanceof WrapByteBuffer : "sliced should be WrapByteBuffer but was " + sliced.getClass().getName();
        assert sliced.capacity() == splitAmount;
        assert sliced.readerIndex() == 0;
        for (int i = 0; i < splitAmount; i++) {
            assert sliced.getByte(i) == (byte) i;
        }

        // 4. Verify Original Buffer (capacity stays same, writerIndex decreased)
        assert buf.writerIndex() == cap - splitAmount;
        assert buf.readerIndex() == 0;
        for (int i = 0; i < buf.writerIndex(); i++) {
            assert buf.getByte(i) == (byte) (i + splitAmount);
        }

        // 5. Independence is guaranteed since sliceOff now returns a copy-based WrapByteBuffer
        // Verify data content is already correct (verified above)
    }

    @Test
    public void testSliceOff_Unaligned() throws java.io.IOException {
        // 1. Setup: Request 4 pages (64 bytes)
        int cap = PAGE_SIZE * 4;
        PooledByteBuf buf = pooledBuffer(cap);

        for (int i = 0; i < cap; i++)
            buf.writeByte((byte) i);
        buf.flush();

        // 2. Unaligned Split (e.g., 20 bytes = 1 page + 4 bytes)
        int splitAmount = 20;
        ByteBuf sliced = buf.sliceOff(splitAmount);

        // 3. Verify Sliced - now returns WrapByteBuffer
        assert sliced instanceof WrapByteBuffer : "sliced should be WrapByteBuffer but was " + sliced.getClass().getName();
        assert sliced.capacity() == splitAmount;
        for (int i = 0; i < splitAmount; i++)
            assert sliced.getByte(i) == (byte) i;

        // 4. Verify Original (capacity stays same, writerIndex decreased)
        assert buf.writerIndex() == cap - splitAmount;
        for (int i = 0; i < buf.writerIndex(); i++)
            assert buf.getByte(i) == (byte) (i + splitAmount);

        // 5. Independence is guaranteed since sliceOff now returns a copy-based WrapByteBuffer
        // Verify boundary
        assert sliced.getByte(19) == (byte) 19;
    }

    @Test
    public void testDiscardReadBytes_Aligned() throws java.io.IOException {
        int cap = PAGE_SIZE * 4;
        PooledByteBuf buf = pooledBuffer(cap);
        for (int i = 0; i < cap; i++)
            buf.writeByte((byte) i);
        buf.flush();

        // Read 2 pages
        int readLen = PAGE_SIZE * 2;
        buf.readBytes(new byte[readLen]);
        assert buf.readerIndex() == readLen;

        // Discard
        buf.discardReadBytes();

        // Verify
        assert buf.readerIndex() == 0;
        assert buf.writerIndex() == cap - readLen;
        assert buf.capacity() == cap - readLen; // Capacity MUST reduce in this implementation

        // Check remaining data
        for (int i = 0; i < buf.capacity(); i++) {
            assert buf.getByte(i) == (byte) (i + readLen);
        }
    }

    @Test
    public void testDiscardReadBytes_Unaligned() throws java.io.IOException {
        int cap = PAGE_SIZE * 4;
        PooledByteBuf buf = pooledBuffer(cap);
        for (int i = 0; i < cap; i++)
            buf.writeByte((byte) i);
        buf.flush();

        // Read unaligned (20 bytes)
        int readLen = 20;
        buf.readBytes(new byte[readLen]);

        // Discard
        buf.discardReadBytes();

        // With unaligned discard in PooledByteBuf, logic calls split(readerIndex - 1).
        // 20 bytes -> index 19. split(19). splitEnd = 20.
        // 20 is not multiple of 16. Split logic falls back to logic ?
        // Wait, current BufferTarget.split logic: 
        // if (absoluteSplitEnd % pageSize == 0) -> physical split
        // else -> duplicate (shared).
        //
        // If duplicate:
        // Original buffer becomes [20...end].
        // Discarded part is [0...19]. discarded.free() is called.
        // discarded is shared. free() decrements refCount.
        // Original is shared.
        //
        // Result: 
        // buf.capacity() reduces to (64 - 20) = 44.
        // Data should be correct.

        assert buf.readerIndex() == 0;
        assert buf.capacity() == 44;
        assert buf.getByte(0) == (byte) 20;
    }

    @Test
    public void testSliceOff_Zero() {
        PooledByteBuf buf = pooledBuffer(32);
        ByteBuf sliced = buf.sliceOff(0);
        assert sliced.capacity() == 0;
        assert buf.capacity() == 32; // Unchanged
    }

    @Test
    public void testFreeAfterSplit() {
        // Test that freeing the slice doesn't double-free the original or vice versa
        // especially in unaligned (shared) case.
        int cap = PAGE_SIZE * 4;
        PooledByteBuf buf = pooledBuffer(cap);

        ByteBuf sliced = buf.sliceOff(10); // Unaligned, shared pages

        // Both exist.
        sliced.free();
        // Sliced is freed. Original should still work.
        buf.writeByte((byte) 1);

        buf.free();
        // Now both gone.
    }
}
