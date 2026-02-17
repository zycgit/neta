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
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics for {@link ByteBufAllocator} to support production diagnostics.
 * <p>
 * Tracks cumulative allocation counts and bytes allocated by buffer type
 * (heap, direct, pooled, ring). All counters are thread-safe and lock-free.
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
    private final LongAdder heapAllocations      = new LongAdder();
    private final LongAdder directAllocations    = new LongAdder();
    private final LongAdder pooledAllocations    = new LongAdder();
    private final LongAdder ringAllocations      = new LongAdder();
    private final LongAdder heapBytesAllocated   = new LongAdder();
    private final LongAdder directBytesAllocated = new LongAdder();
    private final LongAdder pooledBytesAllocated = new LongAdder();
    private final LongAdder ringBytesAllocated   = new LongAdder();

    // ---- Package-private recording methods ----

    void recordHeapAllocation(int capacity) {
        heapAllocations.increment();
        heapBytesAllocated.add(capacity);
    }

    void recordDirectAllocation(int capacity) {
        directAllocations.increment();
        directBytesAllocated.add(capacity);
    }

    void recordPooledAllocation(int capacity) {
        pooledAllocations.increment();
        pooledBytesAllocated.add(capacity);
    }

    void recordRingAllocation(int capacity) {
        ringAllocations.increment();
        ringBytesAllocated.add(capacity);
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

    /** Total number of pooled buffer allocations. */
    public long pooledAllocations() {
        return pooledAllocations.sum();
    }

    /** Total number of ring buffer allocations. */
    public long ringAllocations() {
        return ringAllocations.sum();
    }

    /** Total bytes allocated through heap buffer allocations (cumulative). */
    public long heapBytesAllocated() {
        return heapBytesAllocated.sum();
    }

    /** Total bytes allocated through direct buffer allocations (cumulative). */
    public long directBytesAllocated() {
        return directBytesAllocated.sum();
    }

    /** Total bytes allocated through pooled buffer allocations (cumulative). */
    public long pooledBytesAllocated() {
        return pooledBytesAllocated.sum();
    }

    /** Total bytes allocated through ring buffer allocations (cumulative). */
    public long ringBytesAllocated() {
        return ringBytesAllocated.sum();
    }

    /** Total number of all allocations (heap + direct + pooled + ring). */
    public long totalAllocations() {
        return heapAllocations.sum() + directAllocations.sum() + pooledAllocations.sum() + ringAllocations.sum();
    }

    /** Total bytes allocated across all allocation types (cumulative). */
    public long totalBytesAllocated() {
        return heapBytesAllocated.sum() + directBytesAllocated.sum() + pooledBytesAllocated.sum() + ringBytesAllocated.sum();
    }

    @Override
    public String toString() {
        return "ByteBufAllocatorMetric{" + "heap(count=" + heapAllocations.sum() + ", bytes=" + heapBytesAllocated.sum() + ")" + ", direct(count=" + directAllocations.sum() + ", bytes=" + directBytesAllocated.sum() + ")" + ", pooled(count=" + pooledAllocations.sum() + ", bytes=" + pooledBytesAllocated.sum() + ")" + ", ring(count=" + ringAllocations.sum() + ", bytes=" + ringBytesAllocated.sum() + ")" + ", total(count=" + totalAllocations() + ", bytes=" + totalBytesAllocated() + ")" + '}';
    }
}
