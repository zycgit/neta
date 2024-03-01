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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 页面池化管理器
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferArena {
    private final BufferPool          bufferPool;
    private final List<PageChunkPool> pagePool;
    private final AtomicInteger       pagePoolCur;
    private final ReentrantLock       poolLock;
    //
    private       double              prevValve;
    private       BufferArena         prev;
    private       double              nextValve;
    private       BufferArena         next;

    BufferArena(BufferPool bufferPool) {
        this.bufferPool = bufferPool;
        this.pagePool = new LinkedList<>();
        this.pagePoolCur = new AtomicInteger(0);
        this.poolLock = bufferPool.getPoolLock();
    }

    public int getChunkCount() {
        return this.pagePool.size();
    }

    public void configMove(double prevValve, BufferArena prev, double nextValve, BufferArena next) {
        this.prevValve = prevValve;
        this.prev = prev;
        this.nextValve = nextValve;
        this.next = next;
    }

    public BufferTarget requestBuffer(int capacity) {
        int cnt = this.pagePool.size();
        if (cnt == 0) {
            return null;
        }

        try {
            // find free
            for (int i = 0; i < cnt; i++) {
                int idx = this.pagePoolCur.incrementAndGet() % cnt;
                PageChunkPool chunkPool = this.pagePool.get(idx);
                PageChunkSplit pages = chunkPool.requestPages(capacity);
                if (pages != null) {
                    Buffer memory = this.bufferPool.getMemory(pages.getMemAddress()); // trigger call triggerUsage method.
                    return new BufferTarget(this.bufferPool.getMemPageSize(), pages, memory);
                }
            }

            // from next BufferArena to request.
            return null;
        } finally {
            this.pagePoolCur.set(this.pagePoolCur.incrementAndGet() % cnt);
        }
    }

    public void offer(PageChunkPool pool) {
        pool.setNotify(this, this::triggerUsage);
        this.pagePool.add(pool);
        triggerUsage(pool);
    }

    private void triggerUsage(PageChunkPool pool) {
        int mov = checkUsage(pool);
        if (mov == 0) {
            return;
        }

        try {
            this.poolLock.lock();
            BufferArena arena = (BufferArena) pool.getCurArena();
            if (mov < 0) {
                if (arena.prev != null) {
                    arena.pagePool.remove(pool);
                    arena.prev.offer(pool);
                }
            } else {
                if (arena.next != null) {
                    arena.pagePool.remove(pool);
                    arena.next.offer(pool);
                }
            }
        } finally {
            this.poolLock.unlock();
        }
    }

    private static int checkUsage(PageChunkPool pool) {
        double usage = pool.getUsage();
        BufferArena arena = (BufferArena) pool.getCurArena();

        if (usage < arena.prevValve && arena.prev != null) {
            return -1;  // move to prev
        } else if (usage >= arena.nextValve) {
            return 1;   // move to next
        } else {
            return 0;
        }
    }

    @Override
    public String toString() {
        poolLock.lock();
        try {
            StringBuilder buf = new StringBuilder();
            if (this.pagePool.size() == 0) {
                return "none";
            }

            for (int i = 0; i < this.pagePool.size(); i++) {
                if (i > 0) {
                    buf.append(ByteBufUtils.NEWLINE);
                }
                PageChunkPool chunkPool = this.pagePool.get(i);
                buf.append(chunkPool);
            }
            return buf.toString();
        } finally {
            poolLock.unlock();
        }
    }
}