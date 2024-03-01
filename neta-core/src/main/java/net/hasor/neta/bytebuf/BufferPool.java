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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 页面池化管理器
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferPool {
    private final   int                  pageSize;
    private final   int                  buddyTreeHeight;
    private final   int                  maximumChunkCount;
    private         long                 memoryChunkSize;
    private final   BufferAllocator      allocator;
    private final   Map<Integer, Buffer> bufferPool;
    private final   ReentrantLock        poolLock;
    //
    protected final BufferArena          qInit;// 000%~025%
    protected final BufferArena          q000; // 001%~050%
    protected final BufferArena          q025; // 025%~075%
    protected final BufferArena          q050; // 050%~100%
    protected final BufferArena          q075; // 075%~100%
    protected final BufferArena          q100; // 100%~MAX
    protected final List<BufferArena>    arenaList;

    public BufferPool(int pageSize, BufferAllocator allocator) {
        this(pageSize, -1, 12, allocator);
    }

    public BufferPool(int pageSize, int maximumChunkCount, int buddyTreeHeight, BufferAllocator allocator) {
        this.pageSize = pageSize;
        this.buddyTreeHeight = buddyTreeHeight;
        this.maximumChunkCount = maximumChunkCount;
        this.allocator = allocator;
        this.bufferPool = new ConcurrentHashMap<>();
        this.poolLock = new ReentrantLock();

        // init Arena
        this.qInit = new BufferArena(this);
        this.q000 = new BufferArena(this);
        this.q025 = new BufferArena(this);
        this.q050 = new BufferArena(this);
        this.q075 = new BufferArena(this);
        this.q100 = new BufferArena(this);

        // qInit <---> q000 <---> q025 <---> q050 <---> q075 <---> q100
        this.qInit.configMove(0, null, 25.0, this.q000);
        this.q000.configMove(1.0, this.qInit, 50.0, this.q025);
        this.q025.configMove(25.0, this.q000, 75.0, this.q050);
        this.q050.configMove(50.0, this.q025, 100.0, this.q075);
        this.q075.configMove(75.0, this.q050, 100.0, this.q100);
        this.q100.configMove(100.0, this.q075, 100.0, null);

        this.arenaList = new ArrayList<>();
        this.arenaList.add(this.q050);
        this.arenaList.add(this.q025);
        this.arenaList.add(this.q000);
        this.arenaList.add(this.qInit);
        this.arenaList.add(this.q075);
    }

    public int getMemPageSize() {
        return pageSize;
    }

    public long getMemChunkSize() {
        return this.memoryChunkSize;
    }

    public long getMemCapacity() {
        if (this.maximumChunkCount == -1) {
            return Long.MAX_VALUE;
        } else {
            return (long) this.maximumChunkCount * (long) this.pageSize * (long) Math.pow(2, this.buddyTreeHeight);
        }
    }

    public ReentrantLock getPoolLock() {
        return this.poolLock;
    }

    public Buffer getMemory(int memAddress) {
        Buffer buffer = this.bufferPool.get(memAddress);
        if (buffer != null) {
            return buffer;
        }
        throw new IllegalStateException("Invalid memory block. The memory block may have been freed.");
    }

    protected BufferTarget requestBuffer(PageChunkSplit pages) {
        Buffer memory = this.getMemory(pages.getMemAddress());
        return new BufferTarget(this.getMemPageSize(), pages, memory);
    }

    public BufferTarget requestBuffer(int capacity) {
        ObjectUtils.checkPositive(capacity, "capacity");

        for (BufferArena arena : this.arenaList) {
            BufferTarget buffer = arena.requestBuffer(capacity);
            if (buffer != null) {
                return buffer;
            }
        }

        try {
            this.poolLock.lock();

            PageChunkPool pool = newAllocator();
            PageChunkSplit pages = pool.requestPages(capacity);
            this.qInit.offer(pool);

            return this.requestBuffer(pages);
        } finally {
            this.poolLock.unlock();
        }
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

    protected PageChunkPool newAllocator() {
        if (this.maximumChunkCount > 0 && this.bufferPool.size() >= this.maximumChunkCount) {
            throw new OutOfMemoryPoolException("OutOfMemory the BufferPool maximum chunks " + this.maximumChunkCount + ", current is " + this.bufferPool.size());
        }

        int memAddress = this.newMemAddress();
        PageChunkPool pool = new PageChunkPool(memAddress, this.pageSize, this.buddyTreeHeight);
        Buffer buffer = this.allocator.allocateBuffer(pool.getCapacity());

        this.bufferPool.put(memAddress, buffer);
        this.memoryChunkSize = this.memoryChunkSize + buffer.capacity();
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
        this.poolLock.lock();
        try {
            String NEWLINE = ByteBufUtils.NEWLINE;
            StringBuilder buf = new StringBuilder()                 //
                    .append("Chunk(s) at 0~25%:").append(NEWLINE)   //
                    .append("\t").append(this.qInit).append(NEWLINE)//
                    .append("Chunk(s) at 0~50%:").append(NEWLINE)   //
                    .append("\t").append(this.q000).append(NEWLINE) //
                    .append("Chunk(s) at 25~75%:").append(NEWLINE)  //
                    .append("\t").append(this.q025).append(NEWLINE) //
                    .append("Chunk(s) at 50~100%:").append(NEWLINE) //
                    .append("\t").append(this.q050).append(NEWLINE) //
                    .append("Chunk(s) at 75~100%:").append(NEWLINE) //
                    .append("\t").append(this.q075).append(NEWLINE) //
                    .append("Chunk(s) at 100%:").append(NEWLINE)    //
                    .append("\t").append(this.q100).append(NEWLINE) //
                    .append("small subpages:");
            //            appendPoolSubPages(buf, smallSubpagePools);
            buf.append(NEWLINE);
            return buf.toString();
        } finally {
            this.poolLock.unlock();
        }
    }
}