package net.hasor.neta.bytebuf;
import org.junit.Test;

import java.nio.ByteBuffer;

public class BufferTest {
    @Test
    public void basicTest_01() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList1 = allocator.requestPages(4);
        BufferTarget buffer1 = pool.requestBuffer(pageList1);
        assert pageList1.capacity() == 4;
        assert buffer1.capacity() == 4;

        PageChunkSplit pageList2 = allocator.requestPages(8);
        BufferTarget buffer2 = pool.requestBuffer(pageList2);
        assert pageList2.capacity() == 8;
        assert buffer2.capacity() == 8;
    }

    @Test
    public void putgetByteTest_01() {
        byte[] bytes = new byte[4096];

        BufferPool pool = new BufferPool(1, c -> ByteBuffer.wrap(bytes));
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit pageList1 = allocator.requestPages(4);
        PageChunkSplit pageList2 = allocator.requestPages(4);
        BufferTarget buffer1 = pool.requestBuffer(pageList1);
        BufferTarget buffer2 = pool.requestBuffer(pageList2);

        buffer1.put(0, (byte) 1);
        assert buffer1.get(0) == 1;
        assert bytes[0] == 1;

        buffer1.put(1, (byte) 2);
        assert buffer1.get(1) == 2;
        assert bytes[1] == 2;

        buffer1.put(2, (byte) 3);
        assert buffer1.get(2) == 3;
        assert bytes[2] == 3;

        buffer1.put(3, (byte) 4);
        assert buffer1.get(3) == 4;
        assert bytes[3] == 4;

        try {
            buffer1.put(4, (byte) 5);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }
        try {
            buffer1.get(4);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        //
        //

        buffer2.put(0, (byte) 10);
        assert buffer2.get(0) == 10;
        assert bytes[4] == 10;

        buffer2.put(1, (byte) 11);
        assert buffer2.get(1) == 11;
        assert bytes[5] == 11;

        buffer2.put(2, (byte) 12);
        assert buffer2.get(2) == 12;
        assert bytes[6] == 12;

        buffer2.put(3, (byte) 13);
        assert buffer2.get(3) == 13;
        assert bytes[7] == 13;

        try {
            buffer2.put(4, (byte) 14);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }
        try {
            buffer2.get(4);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }
    }

    @Test
    public void putBytesTest_01() {
        byte[] bytes = new byte[4096];

        BufferPool pool = new BufferPool(1, c -> ByteBuffer.wrap(bytes));
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(8);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }, 0, 8);
        assert bytes[0] == 1;
        assert bytes[1] == 2;
        assert bytes[2] == 3;
        assert bytes[3] == 4;
        assert bytes[4] == 5;
        assert bytes[5] == 6;
        assert bytes[6] == 7;
        assert bytes[7] == 8;
        assert bytes[8] == 0;

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }, 1, 8);
        assert bytes[0] == 2;
        assert bytes[1] == 3;
        assert bytes[2] == 4;
        assert bytes[3] == 5;
        assert bytes[4] == 6;
        assert bytes[5] == 7;
        assert bytes[6] == 8;
        assert bytes[7] == 9;
        assert bytes[8] == 0;

        buffer.put(3, new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 }, 1, 2);
        assert bytes[0] == 2;
        assert bytes[1] == 3;
        assert bytes[2] == 4;
        assert bytes[3] == 11; // index =3 , srOffset 1
        assert bytes[4] == 12; // index =4 , srOffset 2
        assert bytes[5] == 7;
        assert bytes[6] == 8;
        assert bytes[7] == 9;
        assert bytes[8] == 0;

        buffer.put(6, new byte[] { 1, 2, 3, 4, 5 }, 1, 2);
        assert bytes[6] == 2;
        assert bytes[7] == 3;
        assert bytes[8] == 0;
    }

    @Test
    public void putBytesTest_02() {
        byte[] bytes = new byte[4096];

        BufferPool pool = new BufferPool(1, c -> ByteBuffer.wrap(bytes));
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit keep = allocator.requestPages(8);
        PageChunkSplit pageList = allocator.requestPages(8);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }, 0, 8);
        assert bytes[8] == 1;
        assert bytes[9] == 2;
        assert bytes[10] == 3;
        assert bytes[11] == 4;
        assert bytes[12] == 5;
        assert bytes[13] == 6;
        assert bytes[14] == 7;
        assert bytes[15] == 8;
        assert bytes[16] == 0;

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 }, 1, 8);
        assert bytes[8] == 2;
        assert bytes[9] == 3;
        assert bytes[10] == 4;
        assert bytes[11] == 5;
        assert bytes[12] == 6;
        assert bytes[13] == 7;
        assert bytes[14] == 8;
        assert bytes[15] == 9;
        assert bytes[16] == 0;

        buffer.put(3, new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 }, 1, 2);
        assert bytes[8] == 2;
        assert bytes[9] == 3;
        assert bytes[10] == 4;
        assert bytes[11] == 11; // index =3 , srOffset 1
        assert bytes[12] == 12; // index =4 , srOffset 2
        assert bytes[13] == 7;
        assert bytes[14] == 8;
        assert bytes[15] == 9;
        assert bytes[16] == 0;

        buffer.put(6, new byte[] { 1, 2, 3, 4, 5 }, 1, 2);
        assert bytes[14] == 2;
        assert bytes[15] == 3;
        assert bytes[16] == 0;
    }

    @Test
    public void putBytesTest_03() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = pool.requestBuffer(pageList);

        try {
            buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 1, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            buffer.put(3, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 1, 2);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 1, encounter 2");
        }
    }

    @Test
    public void getBytesTest_01() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        byte[] dat1 = new byte[9];
        buffer.get(0, dat1, 0, 8);
        assert dat1[0] == 1;
        assert dat1[1] == 2;
        assert dat1[2] == 3;
        assert dat1[3] == 4;
        assert dat1[4] == 5;
        assert dat1[5] == 6;
        assert dat1[6] == 7;
        assert dat1[7] == 8;
        assert dat1[8] == 0;

        byte[] dat2 = new byte[9];
        buffer.get(3, dat2, 0, 8);
        assert dat2[0] == 4;
        assert dat2[1] == 5;
        assert dat2[2] == 6;
        assert dat2[3] == 7;
        assert dat2[4] == 8;
        assert dat2[5] == 9;
        assert dat2[6] == 10;
        assert dat2[7] == 11;
        assert dat2[8] == 0;

        byte[] dat3 = new byte[9];
        buffer.get(3, dat3, 3, 3);
        assert dat3[0] == 0;
        assert dat3[1] == 0;
        assert dat3[2] == 0;
        assert dat3[3] == 4;
        assert dat3[4] == 5;
        assert dat3[5] == 6;
        assert dat3[6] == 0;
        assert dat3[7] == 0;
        assert dat3[8] == 0;

        byte[] dat4 = new byte[9];
        buffer.get(6, dat4, 3, 2);
        assert dat4[0] == 0;
        assert dat4[1] == 0;
        assert dat4[2] == 0;
        assert dat4[3] == 7;// dstOffset = 3, index =6
        assert dat4[4] == 8;// dstOffset = 4, index =7
        assert dat4[5] == 0;
    }

    @Test
    public void getBytesTest_02() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit keep = allocator.requestPages(8);
        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        byte[] dat1 = new byte[9];
        buffer.get(0, dat1, 0, 8);
        assert dat1[0] == 1;
        assert dat1[1] == 2;
        assert dat1[2] == 3;
        assert dat1[3] == 4;
        assert dat1[4] == 5;
        assert dat1[5] == 6;
        assert dat1[6] == 7;
        assert dat1[7] == 8;
        assert dat1[8] == 0;

        byte[] dat2 = new byte[9];
        buffer.get(3, dat2, 0, 8);
        assert dat2[0] == 4;
        assert dat2[1] == 5;
        assert dat2[2] == 6;
        assert dat2[3] == 7;
        assert dat2[4] == 8;
        assert dat2[5] == 9;
        assert dat2[6] == 10;
        assert dat2[7] == 11;
        assert dat2[8] == 0;

        byte[] dat3 = new byte[9];
        buffer.get(3, dat3, 3, 3);
        assert dat3[0] == 0;
        assert dat3[1] == 0;
        assert dat3[2] == 0;
        assert dat3[3] == 4;
        assert dat3[4] == 5;
        assert dat3[5] == 6;
        assert dat3[6] == 0;
        assert dat3[7] == 0;
        assert dat3[8] == 0;

        byte[] dat4 = new byte[9];
        buffer.get(6, dat4, 3, 2);
        assert dat4[0] == 0;
        assert dat4[1] == 0;
        assert dat4[2] == 0;
        assert dat4[3] == 7;// dstOffset = 3, index =6
        assert dat4[4] == 8;// dstOffset = 4, index =7
        assert dat4[5] == 0;
    }

    @Test
    public void getBytesTest_03() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = pool.requestBuffer(pageList);

        try {
            buffer.get(0, new byte[8], 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            buffer.get(3, new byte[8], 1, 2);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 1, encounter 2");
        }
    }

    @Test
    public void putByteBufferTest_01() {
        byte[] bytes = new byte[4096];

        BufferPool pool = new BufferPool(1, c -> ByteBuffer.wrap(bytes));
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(8);
        BufferTarget buffer = pool.requestBuffer(pageList);

        ByteBuffer wrap1 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 });
        wrap1.flip();
        buffer.put(0, wrap1, 0, 8);
        assert bytes[0] == 1;
        assert bytes[1] == 2;
        assert bytes[2] == 3;
        assert bytes[3] == 4;
        assert bytes[4] == 5;
        assert bytes[5] == 6;
        assert bytes[6] == 7;
        assert bytes[7] == 8;
        assert bytes[8] == 0;

        ByteBuffer wrap2 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 });
        wrap2.flip();
        buffer.put(0, wrap2, 1, 8);
        assert bytes[0] == 2;
        assert bytes[1] == 3;
        assert bytes[2] == 4;
        assert bytes[3] == 5;
        assert bytes[4] == 6;
        assert bytes[5] == 7;
        assert bytes[6] == 8;
        assert bytes[7] == 9;
        assert bytes[8] == 0;

        ByteBuffer wrap3 = ByteBuffer.wrap(new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 });
        wrap3.flip();
        buffer.put(3, wrap3, 1, 2);
        assert bytes[0] == 2;
        assert bytes[1] == 3;
        assert bytes[2] == 4;
        assert bytes[3] == 11; // index =3 , srOffset 1
        assert bytes[4] == 12; // index =4 , srOffset 2
        assert bytes[5] == 7;
        assert bytes[6] == 8;
        assert bytes[7] == 9;
        assert bytes[8] == 0;

        ByteBuffer wrap4 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 });
        wrap4.flip();
        buffer.put(6, wrap4, 1, 2);
        assert bytes[6] == 2;
        assert bytes[7] == 3;
        assert bytes[8] == 0;
    }

    @Test
    public void putByteBufferTest_02() {
        byte[] bytes = new byte[4096];

        BufferPool pool = new BufferPool(1, c -> ByteBuffer.wrap(bytes));
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit keep = allocator.requestPages(8);
        PageChunkSplit pageList = allocator.requestPages(8);
        BufferTarget buffer = pool.requestBuffer(pageList);

        ByteBuffer wrap1 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 });
        wrap1.flip();
        buffer.put(0, wrap1, 0, 8);
        assert bytes[8] == 1;
        assert bytes[9] == 2;
        assert bytes[10] == 3;
        assert bytes[11] == 4;
        assert bytes[12] == 5;
        assert bytes[13] == 6;
        assert bytes[14] == 7;
        assert bytes[15] == 8;
        assert bytes[16] == 0;

        ByteBuffer wrap2 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 });
        wrap2.flip();
        buffer.put(0, wrap2, 1, 8);
        assert bytes[8] == 2;
        assert bytes[9] == 3;
        assert bytes[10] == 4;
        assert bytes[11] == 5;
        assert bytes[12] == 6;
        assert bytes[13] == 7;
        assert bytes[14] == 8;
        assert bytes[15] == 9;
        assert bytes[16] == 0;

        ByteBuffer wrap3 = ByteBuffer.wrap(new byte[] { 10, 11, 12, 13, 14, 15, 16, 17 });
        wrap3.flip();
        buffer.put(3, wrap3, 1, 2);
        assert bytes[8] == 2;
        assert bytes[9] == 3;
        assert bytes[10] == 4;
        assert bytes[11] == 11; // index =3 , srOffset 1
        assert bytes[12] == 12; // index =4 , srOffset 2
        assert bytes[13] == 7;
        assert bytes[14] == 8;
        assert bytes[15] == 9;
        assert bytes[16] == 0;

        ByteBuffer wrap4 = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 });
        wrap4.flip();
        buffer.put(6, wrap4, 1, 2);
        assert bytes[14] == 2;
        assert bytes[15] == 3;
        assert bytes[16] == 0;
    }

    @Test
    public void putByteBufferTest_03() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = pool.requestBuffer(pageList);

        try {
            ByteBuffer wrap = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            wrap.flip();
            buffer.put(0, wrap, 1, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            ByteBuffer wrap = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            wrap.flip();
            buffer.put(3, wrap, 1, 2);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 1, encounter 2");
        }
    }

    @Test
    public void getByteBufferTest_01() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        ByteBuffer dat1 = ByteBuffer.allocate(9);
        buffer.get(0, dat1, 0, 8);
        assert dat1.get(0) == 1;
        assert dat1.get(1) == 2;
        assert dat1.get(2) == 3;
        assert dat1.get(3) == 4;
        assert dat1.get(4) == 5;
        assert dat1.get(5) == 6;
        assert dat1.get(6) == 7;
        assert dat1.get(7) == 8;
        assert dat1.get(8) == 0;

        ByteBuffer dat2 = ByteBuffer.allocate(9);
        buffer.get(3, dat2, 0, 8);
        assert dat2.get(0) == 4;
        assert dat2.get(1) == 5;
        assert dat2.get(2) == 6;
        assert dat2.get(3) == 7;
        assert dat2.get(4) == 8;
        assert dat2.get(5) == 9;
        assert dat2.get(6) == 10;
        assert dat2.get(7) == 11;
        assert dat2.get(8) == 0;

        ByteBuffer dat3 = ByteBuffer.allocate(9);
        buffer.get(3, dat3, 3, 3);
        assert dat3.get(0) == 0;
        assert dat3.get(1) == 0;
        assert dat3.get(2) == 0;
        assert dat3.get(3) == 4;
        assert dat3.get(4) == 5;
        assert dat3.get(5) == 6;
        assert dat3.get(6) == 0;
        assert dat3.get(7) == 0;
        assert dat3.get(8) == 0;

        ByteBuffer dat4 = ByteBuffer.allocate(9);
        buffer.get(6, dat4, 3, 2);
        assert dat4.get(0) == 0;
        assert dat4.get(1) == 0;
        assert dat4.get(2) == 0;
        assert dat4.get(3) == 7;// dstOffset = 3, index =6
        assert dat4.get(4) == 8;// dstOffset = 4, index =7
        assert dat4.get(5) == 0;
    }

    @Test
    public void getByteBufferTest_02() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();
        PageChunkSplit keep = allocator.requestPages(8);
        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = pool.requestBuffer(pageList);

        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        ByteBuffer dat1 = ByteBuffer.allocate(9);
        buffer.get(0, dat1, 0, 8);
        assert dat1.get(0) == 1;
        assert dat1.get(1) == 2;
        assert dat1.get(2) == 3;
        assert dat1.get(3) == 4;
        assert dat1.get(4) == 5;
        assert dat1.get(5) == 6;
        assert dat1.get(6) == 7;
        assert dat1.get(7) == 8;
        assert dat1.get(8) == 0;

        ByteBuffer dat2 = ByteBuffer.allocate(9);
        buffer.get(3, dat2, 0, 8);
        assert dat2.get(0) == 4;
        assert dat2.get(1) == 5;
        assert dat2.get(2) == 6;
        assert dat2.get(3) == 7;
        assert dat2.get(4) == 8;
        assert dat2.get(5) == 9;
        assert dat2.get(6) == 10;
        assert dat2.get(7) == 11;
        assert dat2.get(8) == 0;

        ByteBuffer dat3 = ByteBuffer.allocate(9);
        buffer.get(3, dat3, 3, 3);
        assert dat3.get(0) == 0;
        assert dat3.get(1) == 0;
        assert dat3.get(2) == 0;
        assert dat3.get(3) == 4;
        assert dat3.get(4) == 5;
        assert dat3.get(5) == 6;
        assert dat3.get(6) == 0;
        assert dat3.get(7) == 0;
        assert dat3.get(8) == 0;

        ByteBuffer dat4 = ByteBuffer.allocate(9);
        buffer.get(6, dat4, 3, 2);
        assert dat4.get(0) == 0;
        assert dat4.get(1) == 0;
        assert dat4.get(2) == 0;
        assert dat4.get(3) == 7;// dstOffset = 3, index =6
        assert dat4.get(4) == 8;// dstOffset = 4, index =7
        assert dat4.get(5) == 0;
    }

    @Test
    public void getByteBufferTest_03() {
        BufferPool pool = new BufferPool(1, ByteBuffer::allocate);
        PageChunkPool allocator = pool.newAllocator();

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = pool.requestBuffer(pageList);

        try {
            buffer.get(0, new byte[8], 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            buffer.get(3, new byte[8], 1, 2);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 1, encounter 2");
        }
    }
}