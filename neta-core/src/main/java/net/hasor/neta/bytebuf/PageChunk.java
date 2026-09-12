/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
/**
 * Free-range descriptor used by the buddy allocator inside a
 * {@link PageChunkPool}.
 * <p>Each instance represents a contiguous <em>currently unallocated</em> page
 * range and participates in the doubly-linked free list for one tree level.
 * When a larger range is split, smaller {@code PageChunk} nodes are exposed;
 * when neighbouring buddies become free again, they can be merged back into a
 * larger free range.
 * <p>The page interval stored here is inclusive on both ends:
 * {@code [fromPage, toPage]}. The corresponding byte window therefore spans
 * from {@code fromPage * pageSize} through
 * {@code (toPage + 1) * pageSize - 1} within the parent pool's memory block.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-15
 * @see PageChunkPool
 * @see PageChunkSplit
 * @see PageRange
 */
class PageChunk implements PageRange {
    private final int           fromPage;
    private final int           toPage;
    private final PageChunkPool ownerPool;
    PageChunk                   parent;
    PageChunk                   prev;
    PageChunk                   next;

    public PageChunk(PageChunkPool ownerPool, int fromPage, int toPage, PageChunk prev, PageChunk parent) {
        this.ownerPool = ownerPool;
        this.fromPage = fromPage;
        this.toPage = toPage;

        this.parent = parent;
        if (prev != null) {
            this.prev = prev;
            this.prev.next = this;
        } else {
            this.prev = null;
        }
    }

    @Override
    public int getMemAddress() {
        return this.ownerPool.getMemAddress();
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
        return this.ownerPool.getPageSize();
    }
}
