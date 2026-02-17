package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BufferPoolTest {
    @Test
    public void poolTest_01() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.getMemCapacity() == 0;

        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer1 = (BufferTarget) pool.requestBuffer(4, ByteBuffer::allocate);
        assert buffer1.capacity() == 4;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 1;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer2 = (BufferTarget) pool.requestBuffer(8, ByteBuffer::allocate);
        assert buffer2.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 1;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer3 = (BufferTarget) pool.requestBuffer(8, ByteBuffer::allocate);
        assert buffer3.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 1;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer4 = (BufferTarget) pool.requestBuffer(8, ByteBuffer::allocate);
        assert buffer4.capacity() == 8;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
        assert pool.getMemChunkSize() == 32;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 0;
        assert pool.q050.getChunkCount() == 1;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer5 = (BufferTarget) pool.requestBuffer(4, ByteBuffer::allocate);
        assert buffer5.capacity() == 4;
        assert pool.getMemPageSize() == 1;
        assert pool.getMemMaxCapacity() == 32;
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
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        BufferTarget buffer1 = (BufferTarget) pool.requestBuffer(16, ByteBuffer::allocate);
        assert buffer1.capacity() == 16;
        assert pool.qInit.getChunkCount() == 0;
        assert pool.q000.getChunkCount() == 0;
        assert pool.q025.getChunkCount() == 1;
        assert pool.q050.getChunkCount() == 0;
        assert pool.q075.getChunkCount() == 0;
        assert pool.q100.getChunkCount() == 0;

        BufferTarget buffer2 = (BufferTarget) pool.requestBuffer(16, ByteBuffer::allocate);
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
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        pool.requestBuffer(1, ByteBuffer::allocate);
        pool.requestBuffer(4, ByteBuffer::allocate);
        pool.requestBuffer(16, ByteBuffer::allocate);
        pool.requestBuffer(8, ByteBuffer::allocate);

        try {
            BufferTarget buffer5 = (BufferTarget) pool.requestBuffer(3, ByteBuffer::allocate);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("OutOfMemory the BufferPool maximum chunks 1, current is 1");
        }
    }

    @Test
    public void poolTest_04() {
        AtomicInteger integer = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return integer.incrementAndGet();
            }
        };

        assert pool.toString().startsWith("Chunk(s) at 0~25%:\n\tnone\nChunk(s) at 0~50%:\n\tnone\nChunk(s) at 25~75%:\n\tnone\nChunk(s) at 50~100%:\n\tnone\nChunk(s) at 75~100%:\n\tnone\nChunk(s) at 100%:\n\tnone");

        pool.requestBuffer(4, ByteBuffer::allocate);
        pool.toString(); // for Coverage
    }
}