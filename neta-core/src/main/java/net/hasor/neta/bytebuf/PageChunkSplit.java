/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A contiguous block of memory that is allocated.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-15
 */
class PageChunkSplit implements PageRange {
    static  RecycleHandler<PageChunkSplit> RECYCLE_HANDLER = new RecycleHandler<PageChunkSplit>() {
        public PageChunkSplit create() {
            return new PageChunkSplit();
        }

        @Override
        public void free(PageChunkSplit tar) {
            RecycleObjectPool.free(PageChunkSplit.class, tar);
        }
    };
    private int                            fromPage;
    private int                            toPage;

    // ------------------------------------------------------------------------
    private int           capacity;
    private PageChunkPool chunkPool;
    private AtomicInteger refCount;
    private boolean       available;

    private PageChunkSplit() {
    }

    void initPageChunk(PageChunkPool chunkPool, int fromPage, int toPage, AtomicInteger refCount) {
        this.chunkPool = chunkPool;
        this.fromPage = fromPage;
        this.toPage = toPage;
        this.capacity = chunkPool.getPageSize() * (toPage - fromPage + 1);
        this.refCount = refCount;
        this.available = true;
    }

    @Override
    public int getMemAddress() {
        return this.chunkPool.getMemAddress();
    }

    @Override
    public int getFromPage() {
        return this.fromPage;
    }

    @Override
    public int getToPage() {
        return this.toPage;
    }

    @Override
    public int getPageSize() {
        return this.chunkPool.getPageSize();
    }

    /** The capacity of this Pages, that is, the maximum number of page. */
    public int capacity() {
        return this.capacity;
    }

    /** Returns if the current {@link PageChunkSplit} is available */
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Release the pages it holds. {@link PageChunkSplit} will become unavailable. This method has the following effect:
     * <ul>
     *  <li>causes {@link #isAvailable()} to return false</li>
     *  <li>Deallocating a Page decrements the reference count by 1 and triggers deallocating when the reference count reaches 0 </li>
     * </ul>
     */
    public void free() {
        if (this.available) {
            this.available = false;
            this.refCount.decrementAndGet();
            if (refCount.get() <= 0) {
                this.chunkPool.free(this);
                this.chunkPool = null;
                this.refCount = null;
                RECYCLE_HANDLER.free(this);
            }
        }
    }

    /**
     * Produces a copy of {@link PageChunkSplit} with the following properties:
     * <ul>
     *     <li>Sharing the same Pages</li>
     *     <li>Increment the Pages reference count</li>
     * </ul>
     */
    public PageChunkSplit duplicate() {
        this.refCount.incrementAndGet();

        PageChunkSplit chunk = RecycleObjectPool.get(PageChunkSplit.class, PageChunkSplit.RECYCLE_HANDLER);
        chunk.initPageChunk(this.chunkPool, this.fromPage, this.toPage, this.refCount);
        return chunk;
    }
}