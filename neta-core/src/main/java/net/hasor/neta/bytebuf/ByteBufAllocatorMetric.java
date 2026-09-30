/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics for {@link ByteBufAllocator} to support production diagnostics.
 * <p>
 * Tracks cumulative allocation counts and bytes allocated by buffer type
 * (heap, direct, pooled, ring, swap, wrap). All counters are thread-safe and lock-free.
 * Uses {@link LongAdder} for minimal CAS contention under multi-thread access.
 * <p>
 * Usage:
 * <pre>{@code
 * ByteBufAllocatorMetric metric = allocator.metric();
 * System.out.println("Heap allocations: " + metric.heapAllocations());
 * System.out.println("Direct bytes: " + metric.directBytesAllocated());
 * System.out.println(metric); // formatted summary
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-16
 */
public final class ByteBufAllocatorMetric {
    private final LongAdder heapAllocations       = new LongAdder();
    private final LongAdder directAllocations     = new LongAdder();
    private final LongAdder heapBytesAllocated    = new LongAdder();
    private final LongAdder directBytesAllocated  = new LongAdder();
    private final LongAdder heapReleases          = new LongAdder();
    private final LongAdder directReleases        = new LongAdder();
    private final LongAdder heapCapacityBalance   = new LongAdder();
    private final LongAdder directCapacityBalance = new LongAdder();

    // ---- Package-private recording methods ----

    void recordAllocation(boolean direct, int capacity) {
        int normalizedCapacity = Math.max(capacity, 0);
        if (direct) {
            directAllocations.increment();
            directBytesAllocated.add(normalizedCapacity);
        } else {
            heapAllocations.increment();
            heapBytesAllocated.add(normalizedCapacity);
        }
    }

    void recordRelease(boolean direct, int capacity) {
        int normalizedCapacity = Math.max(capacity, 0);
        if (direct) {
            directReleases.increment();
            directCapacityBalance.add(-normalizedCapacity);
        } else {
            heapReleases.increment();
            heapCapacityBalance.add(-normalizedCapacity);
        }
    }

    void recordCapacityChange(boolean direct, int delta) {
        if (delta == 0) {
            return;
        }

        if (direct) {
            directCapacityBalance.add(delta);
        } else {
            heapCapacityBalance.add(delta);
        }
    }

    // ---- Public query methods ----

    /** Total number of heap buffer allocations. */
    public long heapAllocations() {
        return heapAllocations.sum();
    }

    /** Total number of direct buffer allocations. */
    public long directAllocations() {
        return directAllocations.sum();
    }

    /** Total bytes allocated through heap buffer allocations (cumulative). */
    public long heapBytesAllocated() {
        return heapBytesAllocated.sum();
    }

    /** Total bytes allocated through direct buffer allocations (cumulative). */
    public long directBytesAllocated() {
        return directBytesAllocated.sum();
    }

    /** Currently active heap buffers; concurrent updates are not an atomic snapshot. */
    public long heapActiveAllocations() {
        // Read releases first so a completed release also publishes its earlier allocation.
        long released = heapReleases.sum();
        return heapAllocations.sum() - released;
    }

    /** Currently active direct buffers. */
    public long directActiveAllocations() {
        long released = directReleases.sum();
        return directAllocations.sum() - released;
    }

    /** Currently active heap bytes. */
    public long heapActiveBytes() {
        long balance = heapCapacityBalance.sum();
        return heapBytesAllocated.sum() + balance;
    }

    /** Currently active direct bytes. */
    public long directActiveBytes() {
        long balance = directCapacityBalance.sum();
        return directBytesAllocated.sum() + balance;
    }

    /** Total number of all allocations (heap + direct). */
    public long totalAllocations() {
        return heapAllocations.sum() + directAllocations.sum();
    }

    /** Total bytes allocated across heap/direct allocation types (cumulative). */
    public long totalBytesAllocated() {
        return heapBytesAllocated.sum() + directBytesAllocated.sum();
    }

    /** Total number of all currently active buffers. */
    public long totalActiveAllocations() {
        return heapActiveAllocations() + directActiveAllocations();
    }

    /** Total bytes currently retained by active buffers. */
    public long totalActiveBytes() {
        return heapActiveBytes() + directActiveBytes();
    }

    @Override
    public String toString() {
        // @formatter:off
        return "ByteBufAllocatorMetric{" +
                "heap(totalCount=" + heapAllocations.sum() +
                    ", totalBytes=" + heapBytesAllocated.sum() +
                    ", activeCount=" + heapActiveAllocations() +
                    ", activeBytes=" + heapActiveBytes() +
                ")" +
                ", direct(totalCount=" + directAllocations.sum() +
                    ", totalBytes=" + directBytesAllocated.sum() +
                    ", activeCount=" + directActiveAllocations() +
                    ", activeBytes=" + directActiveBytes() +
                ")" +
                ", total(totalCount=" + totalAllocations() +
                    ", totalBytes=" + totalBytesAllocated() +
                    ", activeCount=" + totalActiveAllocations() +
                    ", activeBytes=" + totalActiveBytes() +
                ")" +
                '}';
        // @formatter:on
    }
}
