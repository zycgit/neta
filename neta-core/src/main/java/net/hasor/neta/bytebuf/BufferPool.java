/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.RandomUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Memory pool
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferPool {
    //
    protected final BufferArena          qInit;// 000%~025%
    protected final BufferArena          q000; // 001%~050%
    protected final BufferArena          q025; // 025%~075%
    protected final BufferArena          q050; // 050%~100%
    protected final BufferArena          q075; // 075%~100%
    protected final BufferArena          q100; // 100%~MAX
    protected final BufferArena[]        arenaList;
    private final   int                  pageSize;
    private final   int                  buddyTreeHeight;
    private final   int                  maximumChunkCount;
    private final   int                  memoryChunkSize;
    private final   Map<Integer, Buffer> bufferPool;
    private         long                 memoryCapacity;

    public BufferPool(int pageSize) {
        this(pageSize, -1, 12);
    }

    public BufferPool(int pageSize, int maximumChunkCount) {
        this(pageSize, maximumChunkCount, 12);
    }

    public BufferPool(int pageSize, int maximumChunkCount, int buddyTreeHeight) {
        if (maximumChunkCount != -1) {
            ObjectUtils.assertTrue(maximumChunkCount > 0, "if config maximumChunkCount, greater than 0.");
        }
        this.pageSize = pageSize;
        this.buddyTreeHeight = buddyTreeHeight;
        this.maximumChunkCount = maximumChunkCount;
        this.memoryChunkSize = (int) Math.pow(2, buddyTreeHeight);
        this.bufferPool = new ConcurrentHashMap<>();
        Lock shareLock = new ReentrantLock(false);

        // init Arena
        this.qInit = new BufferArena(this, shareLock);
        this.q000 = new BufferArena(this, shareLock);
        this.q025 = new BufferArena(this, shareLock);
        this.q050 = new BufferArena(this, shareLock);
        this.q075 = new BufferArena(this, shareLock);
        this.q100 = new BufferArena(this, shareLock);

        // qInit <---> q000 <---> q025 <---> q050 <---> q075 <---> q100
        this.qInit.configMove(0, null, 25.0, this.q000);
        this.q000.configMove(1.0, this.qInit, 50.0, this.q025);
        this.q025.configMove(25.0, this.q000, 75.0, this.q050);
        this.q050.configMove(50.0, this.q025, 100.0, this.q075);
        this.q075.configMove(75.0, this.q050, 100.0, this.q100);
        this.q100.configMove(100.0, this.q075, 100.0, null);

        this.arenaList = new BufferArena[] { this.q050, this.q025, this.q000, this.qInit, this.q075 };
    }

    public int getMemPageSize() {
        return pageSize;
    }

    public long getMemChunkSize() {
        return (long) this.pageSize * (long) this.memoryChunkSize;
    }

    public long getMemCapacity() {
        return this.memoryCapacity;
    }

    public long getMemMaxCapacity() {
        if (this.maximumChunkCount == -1) {
            return -1;
        } else {
            return (long) this.maximumChunkCount * this.getMemChunkSize();
        }
    }

    public Buffer getMemory(int memAddress) {
        Buffer buffer = this.bufferPool.get(memAddress);
        if (buffer != null) {
            return buffer;
        }
        throw new IllegalStateException("Invalid memory block. The memory block may have been freed.");
    }

    protected Buffer requestBuffer(PageChunkSplit pages) {
        Buffer memory = this.getMemory(pages.getMemAddress());
        BufferTarget buffer = RecycleObjectPool.get(BufferTarget.class, BufferTarget.RECYCLE_HANDLER);
        buffer.initBuffer(this.getMemPageSize(), pages, memory);
        return buffer;
    }

    public Buffer requestBuffer(int capacity, BufferAllocator alloc) {
        ObjectUtils.checkPositive(capacity, "capacity");
        if (capacity > this.memoryChunkSize) {
            BufferWrap buffer = RecycleObjectPool.get(BufferWrap.class, BufferWrap.RECYCLE_HANDLER);
            buffer.initBuffer(alloc.jvmBuffer(capacity));
            return buffer;
        }

        for (BufferArena arena : this.arenaList) {
            BufferTarget buffer = arena.requestBuffer(capacity);
            if (buffer != null) {
                return buffer;
            }
        }

        PageChunkSplit pages = initChunkPool(alloc).requestPages(capacity);
        return this.requestBuffer(pages);
    }

    protected PageChunkPool initChunkPool(BufferAllocator alloc) {
        PageChunkPool pool = newAllocator(alloc);
        this.qInit.lockOffer(pool);
        return pool;
    }

    protected int newMemAddress() {
        while (true) {
            int memAddress = RandomUtils.nextInt();
            if (this.bufferPool.containsKey(memAddress)) {
                continue;
            }
            return memAddress;
        }
    }

    protected synchronized PageChunkPool newAllocator(BufferAllocator alloc) {
        if (this.maximumChunkCount > 0 && this.bufferPool.size() >= this.maximumChunkCount) {
            throw new OutOfMemoryPoolException("OutOfMemory the BufferPool maximum chunks " + this.maximumChunkCount + ", current is " + this.bufferPool.size());
        }

        int memAddress = this.newMemAddress();
        PageChunkPool pool = new PageChunkPool(memAddress, this.pageSize, this.buddyTreeHeight);
        BufferWrap buffer = RecycleObjectPool.get(BufferWrap.class, BufferWrap.RECYCLE_HANDLER);
        buffer.initBuffer(alloc.jvmBuffer(pool.getCapacity()));

        this.bufferPool.put(memAddress, buffer);
        this.memoryCapacity = this.memoryCapacity + buffer.capacity();
        return pool;
    }

    public synchronized void freeAllocator(PageChunkPool allocator) {
        int memAddress = allocator.getMemAddress();
        if (!this.bufferPool.containsKey(memAddress)) {
            return;
        }

        Buffer buffer = this.bufferPool.get(memAddress);
        this.bufferPool.remove(memAddress);
        buffer.free();
    }

    @Override
    public String toString() {
        String NEWLINE = ByteBufUtils.NEWLINE;
        return "Chunk(s) at 0~25%:" + NEWLINE      //
                + "\t" + this.qInit + NEWLINE      //
                + "Chunk(s) at 0~50%:" + NEWLINE   //
                + "\t" + this.q000 + NEWLINE       //
                + "Chunk(s) at 25~75%:" + NEWLINE  //
                + "\t" + this.q025 + NEWLINE       //
                + "Chunk(s) at 50~100%:" + NEWLINE //
                + "\t" + this.q050 + NEWLINE       //
                + "Chunk(s) at 75~100%:" + NEWLINE //
                + "\t" + this.q075 + NEWLINE       //
                + "Chunk(s) at 100%:" + NEWLINE    //
                + "\t" + this.q100 + NEWLINE       //
                + "small subpages:"
                //            appendPoolSubPages(buf, smallSubpagePools);
                + NEWLINE;
    }
}