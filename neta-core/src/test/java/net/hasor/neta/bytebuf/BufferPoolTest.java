package net.hasor.neta.bytebuf;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPoolTest {
    @Test
    public void poolTest_01() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 0, 1, 5, ByteBuffer::allocate) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 0;

        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer1 = pool.requestBuffer(4);
        assert buffer1.capacity() == 4;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 1;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer2 = pool.requestBuffer(8);
        assert buffer2.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 1;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer3 = pool.requestBuffer(8);
        assert buffer3.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 1;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer4 = pool.requestBuffer(8);
        assert buffer4.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 1;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer5 = pool.requestBuffer(4);
        assert buffer5.capacity() == 4;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 1;

        buffer5.free();
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 1;
        assert pool.q100.getChunkCount() == 0;

        buffer4.free();
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 1;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        buffer3.free();
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 1;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        buffer2.free();
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 1;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        buffer1.free();
        assert pool.qInit.getChunkCount() == 1;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;
    }

    @Test
    public void poolTest_02() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 0, 1, 5, ByteBuffer::allocate) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        BufferTarget buffer1 = pool.requestBuffer(16);
        assert buffer1.capacity() == 16;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 1;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer2 = pool.requestBuffer(16);
        assert buffer2.capacity() == 16;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 1;

        buffer1.free();
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 1;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        buffer2.free();
        assert pool.qInit.getChunkCount() == 1;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;
    }

    @Test
    public void poolTest_03() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 0, 1, 5, ByteBuffer::allocate) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        pool.requestBuffer(1);
        pool.requestBuffer(4);
        pool.requestBuffer(16);
        pool.requestBuffer(8);

        try {
            BufferTarget buffer5 = pool.requestBuffer(3);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("OutOfMemory the BufferPool maximum chunks 1, current is 1");
        }
    }

    @Test
    public void poolTest_04() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 0, 1, 5, ByteBuffer::allocate) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        assert pool.toString().startsWith("Chunk(s) at 0~25%:\n\tnone\nChunk(s) at 0~50%:\n\tnone\nChunk(s) at 25~75%:\n\tnone\nChunk(s) at 50~100%:\n\tnone\nChunk(s) at 75~100%:\n\tnone\nChunk(s) at 100%:\n\tnone");

        pool.requestBuffer(4);
        pool.toString(); // for Coverage
    }
}