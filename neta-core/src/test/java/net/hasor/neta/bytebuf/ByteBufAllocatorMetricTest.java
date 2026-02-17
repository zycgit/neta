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
import org.junit.Test;

/**
 * Tests for {@link ByteBufAllocatorMetric}.
 */
public class ByteBufAllocatorMetricTest {

    @Test
    public void test_metric_available_from_allocator() {
        ByteBufAllocatorMetric metric = ByteBufAllocator.DEFAULT.metric();
        assert metric != null;
    }

    @Test
    public void test_heap_allocation_tracked() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long beforeCount = metric.heapAllocations();
        long beforeBytes = metric.heapBytesAllocated();

        ByteBuf buf = alloc.heapBuffer(128);
        try {
            assert metric.heapAllocations() > beforeCount : "heap allocation count should increase";
            assert metric.heapBytesAllocated() > beforeBytes : "heap bytes allocated should increase";
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_direct_allocation_tracked() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long beforeCount = metric.directAllocations();
        long beforeBytes = metric.directBytesAllocated();

        ByteBuf buf = alloc.directBuffer(256);
        try {
            assert metric.directAllocations() > beforeCount : "direct allocation count should increase";
            assert metric.directBytesAllocated() > beforeBytes : "direct bytes allocated should increase";
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_pooled_allocation_tracked() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long beforeCount = metric.pooledAllocations();
        long beforeBytes = metric.pooledBytesAllocated();

        ByteBuf buf = alloc.pooledBuffer(1024);
        try {
            assert metric.pooledAllocations() > beforeCount : "pooled allocation count should increase";
            assert metric.pooledBytesAllocated() > beforeBytes : "pooled bytes allocated should increase";
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_ring_allocation_tracked() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long beforeCount = metric.ringAllocations();
        long beforeBytes = metric.ringBytesAllocated();

        ByteBuf buf = alloc.ringBuffer(64);
        try {
            assert metric.ringAllocations() > beforeCount : "ring allocation count should increase";
            assert metric.ringBytesAllocated() > beforeBytes : "ring bytes allocated should increase";
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_total_allocations() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long totalBefore = metric.totalAllocations();

        ByteBuf buf1 = alloc.heapBuffer(32);
        ByteBuf buf2 = alloc.ringBuffer(32);
        try {
            assert metric.totalAllocations() >= totalBefore + 2 : "total allocations should increase by at least 2";
        } finally {
            buf1.free();
            buf2.free();
        }
    }

    @Test
    public void test_total_bytes_allocated() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long totalBytesBefore = metric.totalBytesAllocated();

        ByteBuf buf = alloc.heapBuffer(200);
        try {
            assert metric.totalBytesAllocated() > totalBytesBefore : "total bytes allocated should increase";
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_multiple_allocations_accumulate() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long countBefore = metric.heapAllocations();

        ByteBuf[] bufs = new ByteBuf[10];
        try {
            for (int i = 0; i < 10; i++) {
                bufs[i] = alloc.heapBuffer(64);
            }
            assert metric.heapAllocations() - countBefore == 10 : "10 allocations should be recorded";
        } finally {
            for (ByteBuf buf : bufs) {
                if (buf != null)
                    buf.free();
            }
        }
    }

    @Test
    public void test_metric_toString() {
        ByteBufAllocatorMetric metric = ByteBufAllocator.DEFAULT.metric();
        String str = metric.toString();
        assert str != null;
        assert str.contains("ByteBufAllocatorMetric");
        assert str.contains("heap");
        assert str.contains("direct");
        assert str.contains("pooled");
        assert str.contains("ring");
        assert str.contains("total");
    }

    @Test
    public void test_metric_is_same_instance() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        assert alloc.metric() == alloc.metric() : "metric() should return the same instance";
    }

    @Test
    public void test_different_allocators_have_independent_metrics() {
        ByteBufAllocatorMetric heapMetric = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.metric();
        ByteBufAllocatorMetric directMetric = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR.metric();
        assert heapMetric != directMetric : "different allocators should have different metrics";
    }

    @Test
    public void test_buffer_default_also_tracked() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBufAllocatorMetric metric = alloc.metric();
        long totalBefore = metric.totalAllocations();

        ByteBuf buf = alloc.buffer(128);
        try {
            assert metric.totalAllocations() > totalBefore : "buffer() should be tracked";
        } finally {
            buf.free();
        }
    }
}
