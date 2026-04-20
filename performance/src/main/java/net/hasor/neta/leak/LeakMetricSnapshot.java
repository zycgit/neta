package net.hasor.neta.leak;

import net.hasor.neta.bytebuf.ByteBufAllocatorMetric;

/**
 * Immutable snapshot of active allocator metrics for leak-oriented benchmarks.
 */
public final class LeakMetricSnapshot {
    private final long totalActiveAllocations;
    private final long totalActiveBytes;

    private LeakMetricSnapshot(long totalActiveAllocations, long totalActiveBytes) {
        this.totalActiveAllocations = totalActiveAllocations;
        this.totalActiveBytes = totalActiveBytes;
    }

    public static LeakMetricSnapshot capture(ByteBufAllocatorMetric metric) {
        return new LeakMetricSnapshot(metric.totalActiveAllocations(), metric.totalActiveBytes());
    }

    public void assertRestored(ByteBufAllocatorMetric metric, String label) {
        long currentActiveAllocations = metric.totalActiveAllocations();
        long currentActiveBytes = metric.totalActiveBytes();
        if (this.totalActiveAllocations != currentActiveAllocations || this.totalActiveBytes != currentActiveBytes) {
            throw new AssertionError(label + " leaked allocator state. baseline(totalActiveAllocations=" + this.totalActiveAllocations + ", totalActiveBytes=" + this.totalActiveBytes + ") current(totalActiveAllocations=" + currentActiveAllocations + ", totalActiveBytes=" + currentActiveBytes + ") metric=" + metric);
        }
    }
}