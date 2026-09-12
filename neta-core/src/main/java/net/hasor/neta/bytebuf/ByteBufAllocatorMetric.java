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
    private final LongAdder heapAllocations         = new LongAdder();
    private final LongAdder directAllocations       = new LongAdder();
    private final LongAdder heapBytesAllocated      = new LongAdder();
    private final LongAdder directBytesAllocated    = new LongAdder();
    private final LongAdder heapActiveAllocations   = new LongAdder();
    private final LongAdder directActiveAllocations = new LongAdder();
    private final LongAdder heapActiveBytes         = new LongAdder();
    private final LongAdder directActiveBytes       = new LongAdder();

    // ---- Package-private recording methods ----

    void recordAllocation(boolean direct, int capacity) {
        int normalizedCapacity = Math.max(capacity, 0);
        if (direct) {
            directAllocations.increment();
            directBytesAllocated.add(normalizedCapacity);
            directActiveAllocations.increment();
            directActiveBytes.add(normalizedCapacity);
        } else {
            heapAllocations.increment();
            heapBytesAllocated.add(normalizedCapacity);
            heapActiveAllocations.increment();
            heapActiveBytes.add(normalizedCapacity);
        }
    }

    void recordRelease(boolean direct, int capacity) {
        int normalizedCapacity = Math.max(capacity, 0);
        if (direct) {
            directActiveAllocations.add(-1);
            directActiveBytes.add(-normalizedCapacity);
        } else {
            heapActiveAllocations.add(-1);
            heapActiveBytes.add(-normalizedCapacity);
        }
    }

    void recordCapacityChange(boolean direct, int delta) {
        if (delta == 0) {
            return;
        }

        if (direct) {
            directActiveBytes.add(delta);
        } else {
            heapActiveBytes.add(delta);
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

    /** Currently active heap buffers. */
    public long heapActiveAllocations() {
        return heapActiveAllocations.sum();
    }

    /** Currently active direct buffers. */
    public long directActiveAllocations() {
        return directActiveAllocations.sum();
    }

    /** Currently active heap bytes. */
    public long heapActiveBytes() {
        return heapActiveBytes.sum();
    }

    /** Currently active direct bytes. */
    public long directActiveBytes() {
        return directActiveBytes.sum();
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
        return heapActiveAllocations.sum() + directActiveAllocations.sum();
    }

    /** Total bytes currently retained by active buffers. */
    public long totalActiveBytes() {
        return heapActiveBytes.sum() + directActiveBytes.sum();
    }

    @Override
    public String toString() {
        // @formatter:off
        return "ByteBufAllocatorMetric{" +
                "heap(totalCount=" + heapAllocations.sum() +
                    ", totalBytes=" + heapBytesAllocated.sum() +
                    ", activeCount=" + heapActiveAllocations.sum() +
                    ", activeBytes=" + heapActiveBytes.sum() +
                ")" +
                ", direct(totalCount=" + directAllocations.sum() +
                    ", totalBytes=" + directBytesAllocated.sum() +
                    ", activeCount=" + directActiveAllocations.sum() +
                    ", activeBytes=" + directActiveBytes.sum() +
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
