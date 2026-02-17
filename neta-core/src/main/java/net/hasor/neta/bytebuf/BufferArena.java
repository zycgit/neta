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
import java.util.concurrent.locks.Lock;

/**
 * Memory pool PageChunkPool list manage
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferArena {
    private final BufferPool                bufferPool;
    private final BufferRing<PageChunkPool> bufferRing;
    private final Lock                      shareLock;
    private       double                    prevValve;
    private       BufferArena               prev;
    private       double                    nextValve;
    private       BufferArena               next;

    BufferArena(BufferPool bufferPool, Lock shareLock) {
        this.bufferPool = bufferPool;
        this.bufferRing = new BufferRing<>();
        this.shareLock = shareLock;
    }

    private static int checkUsage(PageChunkPool pool) {
        double usage = pool.getUsage();
        BufferArena arena = (BufferArena) pool.getOwner();

        if (usage < arena.prevValve && arena.prev != null) {
            return -1;  // move to prev
        } else if (usage >= arena.nextValve) {
            return 1;   // move to next
        } else {
            return 0;
        }
    }

    public int getChunkCount() {
        return this.bufferRing.size();
    }

    public void configMove(double prevValve, BufferArena prev, double nextValve, BufferArena next) {
        this.prevValve = prevValve;
        this.prev = prev;
        this.nextValve = nextValve;
        this.next = next;
    }

    public BufferTarget requestBuffer(int capacity) {
        int cnt = this.bufferRing.size();
        if (cnt == 0) {
            return null;
        }

        // Use per-iteration next() instead of scan() to avoid readLock-writeLock self-deadlock:
        // requestPages() can trigger triggerUsage() -> bufferRing.remove() which needs writeLock,
        // but scan() holds the readLock for the entire iteration, causing self-deadlock.
        for (int i = 0; i < cnt; i++) {
            PageChunkPool chunkPool = this.bufferRing.next();
            if (chunkPool == null) {
                continue;
            }

            PageChunkSplit pages = chunkPool.requestPages(capacity);
            if (pages == null) {
                continue;
            }

            try {
                Buffer memory = this.bufferPool.getMemory(pages.getMemAddress());
                BufferTarget buffer = RecycleObjectPool.get(BufferTarget.RECYCLE_INDEX, BufferTarget.RECYCLE_HANDLER);
                buffer.initBuffer(this.bufferPool.getMemPageSize(), pages, memory);
                return buffer;
            } catch (Exception e) {
                pages.free(); // Prevent buddy allocator page leak
                continue;
            }
        }

        // from next BufferArena to request.
        return null;
    }

    // this method for BufferPool.
    public void lockOffer(PageChunkPool pool) {
        try {
            this.shareLock.lock();
            this.normalOffer(pool);
        } finally {
            this.shareLock.unlock();
        }
    }

    private void normalOffer(PageChunkPool pool) {
        pool.setOwner(this);
        pool.setNotify(cbPool -> {
            // When checkUsage returns 0, it means no movement is needed, normally we return directly.
            // But if it is in the qInit area (this.prev == null) and usage is close to 0, we need to pass through to triggerUsage to execute the free logic.
            // Only qInit is eligible to execute the "return to operating system" operation.
            if (checkUsage(cbPool) == 0 && (this.prev != null || cbPool.getUsage() > 0.000001d)) {
                return;
            }

            try {
                this.shareLock.lock();
                this.triggerUsage(cbPool);
            } finally {
                this.shareLock.unlock();
            }
        });
        this.bufferRing.add(pool);

        if (checkUsage(pool) != 0) {
            this.triggerUsage(pool);
        }
    }

    private void triggerUsage(PageChunkPool pool) {
        int mov = checkUsage(pool);
        if (mov == 0) {
            if (this.prev == null && pool.getUsage() <= 0.000001d) {
                // Keep at least one chunk in qInit as warm cache to avoid
                // costly re-allocation on rapid alloc+free cycles.
                if (this.bufferRing.size() > 1) {
                    this.bufferRing.remove(pool);
                    this.bufferPool.freeAllocator(pool);
                }
            }
            return;
        }

        BufferArena arena = (BufferArena) pool.getOwner();
        if (mov < 0) {
            if (arena.prev != null) {
                arena.bufferRing.remove(pool);
                arena.prev.normalOffer(pool);
            }
        } else {
            if (arena.next != null) {
                arena.bufferRing.remove(pool);
                arena.next.normalOffer(pool);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        if (this.bufferRing.size() == 0) {
            return "none";
        }

        for (int i = 0; i < this.bufferRing.size(); i++) {
            if (i > 0) {
                buf.append(ByteBufUtils.NEWLINE);
            }
            PageChunkPool chunkPool = this.bufferRing.find(i);
            buf.append(chunkPool);
        }
        return buf.toString();
    }
}