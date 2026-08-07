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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.ref.RecycleObjectPool;
/**
 * Live allocation descriptor returned by {@link PageChunkPool}.
 * <p>{@link PageChunk} models free ranges inside the buddy tree, while
 * {@code PageChunkSplit} models a range that is currently in use. The stored
 * page interval is inclusive on both ends and is backed by a reference-counted
 * ownership record so multiple logical views can share the same physical pages.
 * <p>{@code duplicate()} creates another descriptor that shares the same pages
 * and increments the reference count. {@code split(int)} creates an independent
 * head allocation only when the current descriptor is not shared
 * ({@code refCount == 1}).
 * <p>Calling {@link #free()} decrements the shared reference count; only the
 * final release returns the pages to {@link PageChunkPool} and recycles the
 * descriptor object.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-15
 * @see PageChunk
 * @see PageChunkPool
 * @see PageRange
 */
class PageChunkSplit implements PageRange {
    static final RecycleObjectPool.Recycler<PageChunkSplit> RECYCLER = RecycleObjectPool.recycler(//
            PageChunkSplit::new, PageChunkSplit::resetState, PageChunkSplit::onRecycle);
    private final AtomicBoolean                         available       = new AtomicBoolean(false);
    private int                                         fromPage;
    private int                                         toPage;
    // ------------------------------------------------------------------------
    private int           capacity;
    private PageChunkPool chunkPool;
    private AtomicInteger refCount;

    private PageChunkSplit() {
    }

    private void resetState() {
        this.available.set(false);
        this.fromPage = 0;
        this.toPage = 0;
        this.capacity = 0;
        this.chunkPool = null;
        this.refCount = null;
    }

    private void onRecycle() {
        this.resetState();
    }

    void initPageChunk(PageChunkPool chunkPool, int fromPage, int toPage, AtomicInteger refCount) {
        this.chunkPool = chunkPool;
        this.fromPage = fromPage;
        this.toPage = toPage;
        this.capacity = chunkPool.getPageSize() * (toPage - fromPage + 1);
        this.refCount = refCount;
        this.available.set(true);
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
        return this.available.get();
    }

    /**
     * Release the pages it holds. {@link PageChunkSplit} will become unavailable. This method has the following effect:
     * <ul>
     *  <li>causes {@link #isAvailable()} to return false</li>
     *  <li>Deallocating a Page decrements the reference count by 1 and triggers deallocating when the reference count reaches 0 </li>
     * </ul>
     */
    public void free() {
        if (this.available.compareAndSet(true, false)) {
            if (this.refCount.decrementAndGet() <= 0) {
                this.chunkPool.free(this);
                this.chunkPool = null;
                this.refCount = null;
                RECYCLER.recycle(this);
            }
        }
    }

    /**
     * Splits this chunk into two chunks. The first chunk (returned) contains the first {@code pagesForHead} pages.
     * The second chunk (this) contains the remaining pages.
     * <p>This method performs a physical split of the page range if the chunk is not shared.
     * If the chunk is shared (refCount > 1), it returns null.</p>
     * @param pagesForHead the number of pages to move to the new head chunk
     * @return the new head chunk, or null if the split could not be performed
     */
    public PageChunkSplit split(int pagesForHead) {
        if (!this.available.get() || this.refCount.get() > 1) {
            return null;
        }

        int totalPages = this.toPage - this.fromPage + 1;
        if (pagesForHead <= 0 || pagesForHead >= totalPages) {
            return null;
        }

        // New Head Range
        int headFrom = this.fromPage;
        int headTo = this.fromPage + pagesForHead - 1;

        // Create Head Chunk (Independent)
        PageChunkSplit headChunk = PageChunkSplit.RECYCLER.get();
        headChunk.initPageChunk(this.chunkPool, headFrom, headTo, new AtomicInteger(1));

        // Update This (Tail)
        this.fromPage = headTo + 1;
        this.capacity = this.chunkPool.getPageSize() * (this.toPage - this.fromPage + 1);

        return headChunk;
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

        PageChunkSplit chunk = PageChunkSplit.RECYCLER.get();
        chunk.initPageChunk(this.chunkPool, this.fromPage, this.toPage, this.refCount);
        return chunk;
    }
}
