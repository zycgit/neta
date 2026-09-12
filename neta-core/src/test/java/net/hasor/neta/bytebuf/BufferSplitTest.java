/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import org.junit.Test;

public class BufferSplitTest {
    @Test
    public void splitTest_01() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget pat1 = (BufferTarget) pool.requestBuffer(pageList);
        pat1.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);
        assert pat1.capacity() == 16;

        BufferTarget pat2 = pat1.split(3);
        assert pat1.capacity() == 12;
        assert pat2.capacity() == 4;

        BufferTarget pat3 = pat1.split(3);
        assert pat1.capacity() == 8;
        assert pat2.capacity() == 4;
        assert pat3.capacity() == 4;

        BufferTarget pat4 = pat1.split(3);
        assert pat1.capacity() == 4;
        assert pat2.capacity() == 4;
        assert pat3.capacity() == 4;
        assert pat4.capacity() == 4;

        //

        assert pat2.get(0) == 1;
        assert pat2.get(1) == 2;
        assert pat2.get(2) == 3;
        assert pat2.get(3) == 4;

        assert pat3.get(0) == 5;
        assert pat3.get(1) == 6;
        assert pat3.get(2) == 7;
        assert pat3.get(3) == 8;

        assert pat4.get(0) == 9;
        assert pat4.get(1) == 10;
        assert pat4.get(2) == 11;
        assert pat4.get(3) == 12;

        assert pat1.get(0) == 13;
        assert pat1.get(1) == 14;
        assert pat1.get(2) == 15;
        assert pat1.get(3) == 16;
    }

    @Test
    public void splitTest_01_keep() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);
        PageChunkSplit keep = allocator.requestPages(16);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget pat1 = (BufferTarget) pool.requestBuffer(pageList);
        pat1.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);
        assert pat1.capacity() == 16;

        BufferTarget pat2 = pat1.split(3);
        assert pat1.capacity() == 12;
        assert pat2.capacity() == 4;

        BufferTarget pat3 = pat1.split(3);
        assert pat1.capacity() == 8;
        assert pat2.capacity() == 4;
        assert pat3.capacity() == 4;

        BufferTarget pat4 = pat1.split(3);
        assert pat1.capacity() == 4;
        assert pat2.capacity() == 4;
        assert pat3.capacity() == 4;
        assert pat4.capacity() == 4;

        //

        assert pat2.get(0) == 1;
        assert pat2.get(1) == 2;
        assert pat2.get(2) == 3;
        assert pat2.get(3) == 4;

        assert pat3.get(0) == 5;
        assert pat3.get(1) == 6;
        assert pat3.get(2) == 7;
        assert pat3.get(3) == 8;

        assert pat4.get(0) == 9;
        assert pat4.get(1) == 10;
        assert pat4.get(2) == 11;
        assert pat4.get(3) == 12;

        assert pat1.get(0) == 13;
        assert pat1.get(1) == 14;
        assert pat1.get(2) == 15;
        assert pat1.get(3) == 16;
    }

    @Test
    public void splitTest_02() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        try {
            pat1.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat2.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat3.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat4.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }
    }

    @Test
    public void splitTest_02_keep() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);
        PageChunkSplit keep = allocator.requestPages(16);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        buffer.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }, 0, 16);

        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        try {
            pat1.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat2.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat3.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }

        try {
            pat4.get(4);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer index out of range, expect 0 ~ 3, encounter 4");
        }
    }

    @Test
    public void splitTest_03() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        try {
            pat1.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat2.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat3.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat4.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }
    }

    @Test
    public void splitTest_03_keep() {
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(ByteBuffer::allocate);
        PageChunkSplit keep = allocator.requestPages(16);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        try {
            pat1.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat2.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat3.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }

        try {
            pat4.put(0, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 0, 8);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer length out of range, expect 0 ~ 4, encounter 8");
        }
    }

    @Test
    public void splitTest_04() {
        byte[] bytes = new byte[4096];
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(c -> ByteBuffer.wrap(bytes));

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        pat1.put(0, new byte[] { 1, 2, 3, 4 }, 0, 4);
        assert bytes[12] == 1;
        assert bytes[13] == 2;
        assert bytes[14] == 3;
        assert bytes[15] == 4;

        pat2.put(0, new byte[] { 5, 6, 7, 8 }, 0, 4);
        assert bytes[0] == 5;
        assert bytes[1] == 6;
        assert bytes[2] == 7;
        assert bytes[3] == 8;

        pat3.put(0, new byte[] { 9, 10, 11, 12 }, 0, 4);
        assert bytes[4] == 9;
        assert bytes[5] == 10;
        assert bytes[6] == 11;
        assert bytes[7] == 12;

        pat4.put(0, new byte[] { 13, 14, 15, 16 }, 0, 4);
        assert bytes[8] == 13;
        assert bytes[9] == 14;
        assert bytes[10] == 15;
        assert bytes[11] == 16;
    }

    @Test
    public void splitTest_04_keep() {
        byte[] bytes = new byte[4096];
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(c -> ByteBuffer.wrap(bytes));
        PageChunkSplit keep = allocator.requestPages(16);

        PageChunkSplit pageList = allocator.requestPages(16);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer;
        BufferTarget pat2 = pat1.split(3);
        BufferTarget pat3 = pat1.split(3);
        BufferTarget pat4 = pat1.split(3);

        pat1.put(0, new byte[] { 1, 2, 3, 4 }, 0, 4);
        assert bytes[28] == 1;
        assert bytes[29] == 2;
        assert bytes[30] == 3;
        assert bytes[31] == 4;

        pat2.put(0, new byte[] { 5, 6, 7, 8 }, 0, 4);
        assert bytes[16] == 5;
        assert bytes[17] == 6;
        assert bytes[18] == 7;
        assert bytes[19] == 8;

        pat3.put(0, new byte[] { 9, 10, 11, 12 }, 0, 4);
        assert bytes[20] == 9;
        assert bytes[21] == 10;
        assert bytes[22] == 11;
        assert bytes[23] == 12;

        pat4.put(0, new byte[] { 13, 14, 15, 16 }, 0, 4);
        assert bytes[24] == 13;
        assert bytes[25] == 14;
        assert bytes[26] == 15;
        assert bytes[27] == 16;
    }

    @Test
    public void splitTest_05() {
        byte[] bytes = new byte[4096];
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(c -> ByteBuffer.wrap(bytes));

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer.split(0);
        BufferTarget pat3 = buffer.split(0);
        BufferTarget pat4 = buffer.split(0);

        try {
            buffer.split(0);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer only 1 byte and cannot be split.");
        }
    }

    @Test
    public void splitTest_05_keep() {
        byte[] bytes = new byte[4096];
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(c -> ByteBuffer.wrap(bytes));
        PageChunkSplit keep = allocator.requestPages(16);

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer.split(0);
        BufferTarget pat3 = buffer.split(0);
        BufferTarget pat4 = buffer.split(0);

        try {
            buffer.split(0);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().equals("Buffer only 1 byte and cannot be split.");
        }
    }

    @Test
    public void splitAndFreeTest_01() {
        byte[] bytes = new byte[4096];
        BufferPool pool = new BufferPool(1);
        PageChunkPool allocator = pool.newAllocator(c -> ByteBuffer.wrap(bytes));

        PageChunkSplit pageList = allocator.requestPages(4);
        BufferTarget buffer = (BufferTarget) pool.requestBuffer(pageList);
        BufferTarget pat1 = buffer.split(0);
        BufferTarget pat2 = buffer.split(0);
        BufferTarget pat3 = buffer.split(0);

        PageChunkSplit test1 = allocator.requestPages(4);
        buffer.free();
        assert test1.getFromPage() != 0;

        PageChunkSplit test2 = allocator.requestPages(4);
        pat1.free();
        assert test2.getFromPage() != 0;

        PageChunkSplit test3 = allocator.requestPages(4);
        pat2.free();
        assert test3.getFromPage() != 0;

        PageChunkSplit test4 = allocator.requestPages(4);
        pat3.free();
        assert test4.getFromPage() != 0;

        PageChunkSplit test5 = allocator.requestPages(4);
        assert test5.getFromPage() == 0;
    }
}
