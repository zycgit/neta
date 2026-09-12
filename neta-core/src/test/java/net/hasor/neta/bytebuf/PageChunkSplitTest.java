/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import org.junit.Test;

/**
 * Tests for PageChunkSplit: split, duplicate, free, and lifecycle edge cases.
 */
public class PageChunkSplitTest {

    private PageChunkPool createPool(int pageSize, int totalPages) {
        return new PageChunkPool(1000, pageSize, totalPages);
    }

    // ========================================================================
    // split() tests
    // ========================================================================

    @Test
    public void split_validRange_returnsHead() {
        PageChunkPool pool = createPool(1, 8);
        PageChunkSplit chunk = pool.requestPages(8);
        assert chunk != null;
        assert chunk.getFromPage() == 0;
        assert chunk.getToPage() == 7;

        PageChunkSplit head = chunk.split(3);
        assert head != null;
        assert head.getFromPage() == 0;
        assert head.getToPage() == 2;
        assert head.capacity() == 3;

        // tail (this) should have remaining pages
        assert chunk.getFromPage() == 3;
        assert chunk.getToPage() == 7;
        assert chunk.capacity() == 5;

        head.free();
        chunk.free();
    }

    @Test
    public void split_singlePageHead_returnsOnePage() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;

        PageChunkSplit head = chunk.split(1);
        assert head != null;
        assert head.getFromPage() == 0;
        assert head.getToPage() == 0;
        assert head.capacity() == 1;

        assert chunk.getFromPage() == 1;
        assert chunk.getToPage() == 3;
        assert chunk.capacity() == 3;

        head.free();
        chunk.free();
    }

    @Test
    public void split_zeroPages_returnsNull() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;

        PageChunkSplit head = chunk.split(0);
        assert head == null;
        assert chunk.capacity() == 4; // unchanged

        chunk.free();
    }

    @Test
    public void split_allPages_returnsNull() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;

        PageChunkSplit head = chunk.split(4); // pagesForHead == totalPages
        assert head == null;
        assert chunk.capacity() == 4; // unchanged

        chunk.free();
    }

    @Test
    public void split_negativePagesForHead_returnsNull() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;

        PageChunkSplit head = chunk.split(-1);
        assert head == null;

        chunk.free();
    }

    @Test
    public void split_multipleSplits_chainedCorrectly() {
        // treeHeight=4 → 2^4=16 pages; requestPages(16) allocates all 16
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(16);
        assert chunk != null;
        assert chunk.getFromPage() == 0 && chunk.getToPage() == 15;

        // Split off 4 pages
        PageChunkSplit head1 = chunk.split(4);
        assert head1 != null;
        assert head1.getFromPage() == 0 && head1.getToPage() == 3;
        assert chunk.getFromPage() == 4 && chunk.getToPage() == 15;

        // Split off 4 more from the tail
        PageChunkSplit head2 = chunk.split(4);
        assert head2 != null;
        assert head2.getFromPage() == 4 && head2.getToPage() == 7;
        assert chunk.getFromPage() == 8 && chunk.getToPage() == 15;

        head1.free();
        head2.free();
        chunk.free();
    }

    @Test
    public void split_afterFree_returnsNull() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;

        chunk.free();
        assert !chunk.isAvailable();

        // split on freed chunk should return null
        PageChunkSplit head = chunk.split(2);
        assert head == null;
    }

    // ========================================================================
    // duplicate() tests
    // ========================================================================

    @Test
    public void duplicate_sharesPages_incrementsRefCount() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk != null;
        assert chunk.isAvailable();

        PageChunkSplit dup = chunk.duplicate();
        assert dup != null;
        assert dup.getFromPage() == chunk.getFromPage();
        assert dup.getToPage() == chunk.getToPage();
        assert dup.capacity() == chunk.capacity();
        assert dup.isAvailable();

        // Free original — dup should still be available (refCount was incremented)
        chunk.free();
        assert !chunk.isAvailable();
        assert dup.isAvailable();

        // Free dup — now refCount reaches 0, pages are released
        dup.free();
        assert !dup.isAvailable();
    }

    @Test
    public void duplicate_freeOrder_reversed() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        PageChunkSplit dup = chunk.duplicate();

        // Free dup first, then original
        dup.free();
        assert !dup.isAvailable();
        assert chunk.isAvailable(); // original still has refCount

        chunk.free();
        assert !chunk.isAvailable();
    }

    @Test
    public void duplicate_multipleDuplicates_allSharePages() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);

        PageChunkSplit dup1 = chunk.duplicate();
        PageChunkSplit dup2 = chunk.duplicate();
        PageChunkSplit dup3 = chunk.duplicate();

        // Free original and first two dups
        chunk.free();
        assert !chunk.isAvailable();
        assert dup1.isAvailable();

        dup1.free();
        assert !dup1.isAvailable();
        assert dup2.isAvailable();

        dup2.free();
        assert !dup2.isAvailable();
        assert dup3.isAvailable();

        // Last dup free releases the pages
        dup3.free();
        assert !dup3.isAvailable();
    }

    @Test
    public void split_afterDuplicate_returnsNull() {
        // split requires refCount == 1, after duplicate refCount > 1
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        PageChunkSplit dup = chunk.duplicate();

        // split should fail because refCount > 1
        PageChunkSplit head = chunk.split(2);
        assert head == null;

        chunk.free();
        dup.free();
    }

    // ========================================================================
    // free() tests
    // ========================================================================

    @Test
    public void free_idempotent_secondFreeIsNoop() {
        PageChunkPool pool = createPool(1, 4);
        PageChunkSplit chunk = pool.requestPages(4);
        assert chunk.isAvailable();

        chunk.free();
        assert !chunk.isAvailable();

        // Second free should not crash (available guard)
        chunk.free();
        assert !chunk.isAvailable();
    }

    @Test
    public void free_pagesReturnedToPool() {
        // treeHeight=3 → 2^3=8 pages; requestPages(4) → 4 pages = 50%
        PageChunkPool pool = createPool(1, 3);
        PageChunkSplit chunk = pool.requestPages(4);
        assert pool.getUsage() == 50.0;

        chunk.free();
        assert pool.getUsage() == 0.0;

        // Pages should be reusable
        PageChunkSplit newChunk = pool.requestPages(4);
        assert newChunk != null;
        assert newChunk.getFromPage() == 0;
        newChunk.free();
    }

    // ========================================================================
    // capacity() and pageSize tests
    // ========================================================================

    @Test
    public void capacity_reflectsPageSizeTimesPages() {
        // pageSize=4, treeHeight=3 → 2^3=8 pages, capacity=4*8=32 bytes
        // requestPages(16) means 16 bytes = 4 pages (16/4)
        PageChunkPool pool = createPool(4, 3);
        PageChunkSplit chunk = pool.requestPages(16);
        assert chunk != null;
        assert chunk.capacity() == 16; // 4 pages * 4 bytes/page = 16
        assert chunk.getPageSize() == 4;

        chunk.free();
    }

    @Test
    public void split_capacity_updateCorrectly() {
        // pageSize=1, treeHeight=3 → 2^3=8 pages, capacity=8 bytes
        // requestPages(8) allocates all 8 pages
        PageChunkPool pool = createPool(1, 3);
        PageChunkSplit chunk = pool.requestPages(8);
        assert chunk.capacity() == 8;

        PageChunkSplit head = chunk.split(4);
        assert head != null;
        assert head.capacity() == 4;
        assert chunk.capacity() == 4;

        head.free();
        chunk.free();
    }

    // ========================================================================
    // Pool reuse after split+free
    // ========================================================================

    @Test
    public void splitAndFreeHead_poolReusesPages() {
        PageChunkPool pool = createPool(1, 8);
        PageChunkSplit chunk = pool.requestPages(8);

        PageChunkSplit head = chunk.split(4);
        assert head != null;

        // Free head — pages 0-3 should return to pool
        head.free();

        // Pool should be able to allocate from freed head pages
        PageChunkSplit reused = pool.requestPages(4);
        assert reused != null;
        assert reused.getFromPage() == 0;
        assert reused.getToPage() == 3;

        reused.free();
        chunk.free();
    }

    @Test
    public void splitAndFreeTail_poolReusesPages() {
        PageChunkPool pool = createPool(1, 8);
        PageChunkSplit chunk = pool.requestPages(8);

        PageChunkSplit head = chunk.split(4);
        assert head != null;

        // Free tail — pages 4-7 should return to pool
        chunk.free();

        // Pool should be able to allocate from freed tail pages
        PageChunkSplit reused = pool.requestPages(4);
        assert reused != null;
        assert reused.getFromPage() == 4;
        assert reused.getToPage() == 7;

        reused.free();
        head.free();
    }
}
