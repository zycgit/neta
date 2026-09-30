/*
 * Copyright 2015-2022 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.neta.codec.http;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.data.ProtoQueue;
import org.junit.Test;
import static org.junit.Assert.*;

public class GroupedHeaderOwnershipTest {
    private static ByteBuf input(String value, int kind) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (kind == 0)
            return ByteBuf.wrap(bytes);
        ByteBuffer buffer = kind == 1 ? ByteBuffer.allocate(bytes.length) : ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return ByteBuf.wrap(buffer).asReadOnly();
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void assertRetainedWeight(Object storage) throws Exception {
        Object[] data = (Object[]) field(storage, "data");
        int[] ranges = (int[]) field(storage, "ranges");
        Object[] sources = (Object[]) field(storage, "extraSources");
        long expected = 128L + (data == null ? 0 : data.length * 8L + ranges.length * 4L) + (sources == null ? 0 : 32L + sources.length * 12L);
        Method weight = storage.getClass().getDeclaredMethod("retainedWeight");
        weight.setAccessible(true);
        assertEquals((int) Math.min(Integer.MAX_VALUE, expected), ((Number) weight.invoke(storage)).intValue());
    }

    @Test
    public void retainedWeightFollowsCapacityGrowthAndSurvivesRecycling() throws Exception {
        Class<?> type = Class.forName(HeaderEntryStore.class.getName() + "$Storage");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object storage = constructor.newInstance();
        assertRetainedWeight(storage);
        for (String methodName : new String[] { "ensureCapacity", "ensureSourceCapacity" }) {
            Method method = type.getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            for (int required : new int[] { 0, 1, 2, 4, 5, 17, 65, 1, 0 }) {
                method.invoke(storage, required);
                assertRetainedWeight(storage);
            }
        }
        for (String methodName : new String[] { "acquire", "recycle", "acquire" }) {
            Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(storage);
            assertRetainedWeight(storage);
        }
    }

    @Test
    public void oneReferencePerSourceRegardlessOfFieldCount() {
        for (int kind = 0; kind < 3; kind++) {
            for (int count : new int[] { 1, 4, 64, 65, 129 }) {
                ByteBuf source = input("Name:42", kind);
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                try {
                    for (int i = 0; i < count; i++)
                        headers.addDecodedHeader(source, 0, 4, 5, 2);
                    assertEquals(2, source.refCnt());
                    assertEquals(count, headers.getValues("name").size());
                    assertEquals(2, source.refCnt());
                    assertEquals(1, headers.headerNames().size());
                    assertEquals(1, source.refCnt());
                    headers.release();
                    assertEquals(1, source.refCnt());
                } finally {
                    headers.release();
                    source.release();
                }
            }
        }
    }

    @Test
    public void extractedEntrySurvivesContainerReleaseAndReuse() {
        ByteBuf source = input("Name:42", 2);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        DefaultHttpHeaderEntry retained = null;
        try {
            for (int i = 0; i < 4; i++)
                headers.addDecodedHeader(source, 0, 4, 5, 2);
            retained = headers.headerEntries().get(0).retain();
            assertEquals(3, source.refCnt());
            headers.release();
            assertEquals(2, source.refCnt());
            headers.addHeader("Other", "value");
            assertEquals("42", retained.getValue());
            assertEquals("Name", retained.getName());
            assertEquals(1, source.refCnt());
        } finally {
            headers.release();
            if (retained != null)
                retained.release();
            source.release();
        }
    }

    @Test
    public void emptiedPrimaryDoesNotDuplicateExistingSecondaryOwnership() {
        ByteBuf first = input("First:1", 0);
        ByteBuf second = input("Other:2", 0);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        try {
            headers.addDecodedHeader(first, 0, 5, 6, 1);
            headers.addDecodedHeader(second, 0, 5, 6, 1);
            headers.headerNames();
            assertEquals("1", headers.getString("first"));
            assertEquals(1, first.refCnt());
            assertEquals(2, second.refCnt());
            headers.addDecodedHeader(second, 0, 5, 6, 1);
            assertEquals(2, second.refCnt());
            headers.headerNames();
            assertEquals(List.of("2", "2"), headers.getValues("Other"));
            assertEquals(1, second.refCnt());
        } finally {
            headers.release();
            assertEquals(1, first.refCnt());
            assertEquals(1, second.refCnt());
            first.release();
            second.release();
        }
    }

    @Test
    public void transferIntoEmptyContainerMovesMetadataAndOwnership() throws Exception {
        ByteBuf source = input("Name:42", 0);
        DefaultHttpHeaders original = new DefaultHttpHeaders();
        DefaultHttpHeaders target = new DefaultHttpHeaders();
        try {
            for (int i = 0; i < 4; i++)
                original.addDecodedHeader(source, 0, 4, 5, 2);
            Object storage = field(original.headerEntries(), "storage");
            target.transferHeaders(original);
            assertNull(field(original.headerEntries(), "storage"));
            assertSame(storage, field(target.headerEntries(), "storage"));
            original.release();
            assertEquals(2, source.refCnt());
            assertEquals(4, target.getValues("Name").size());
            target.release();
            assertEquals(1, source.refCnt());
        } finally {
            original.release();
            target.release();
            source.release();
        }
    }

    @Test
    public void mergingContainersCoalescesSharedSourcesAndPreservesCopies() {
        List<ByteBuf> sources = new ArrayList<>();
        DefaultHttpHeaders original = new DefaultHttpHeaders();
        DefaultHttpHeaders target = new DefaultHttpHeaders();
        DefaultHttpHeaders copy = new DefaultHttpHeaders();
        try {
            for (int i = 0; i < 9; i++) {
                ByteBuf source = input("Name:" + i, i % 3);
                sources.add(source);
                original.addDecodedHeader(source, 0, 4, 5, 1);
                original.addDecodedHeader(source, 0, 4, 5, 1);
                target.addDecodedHeader(source, 0, 4, 5, 1);
                assertEquals(3, source.refCnt());
            }
            target.transferHeaders(original);
            original.release();
            for (ByteBuf source : sources)
                assertEquals(2, source.refCnt());
            assertEquals(27, target.headerSize());
            copy.appendHeaders(target);
            for (ByteBuf source : sources)
                assertEquals(1, source.refCnt());
            target.release();
            assertEquals(27, copy.getValues("Name").size());
        } finally {
            original.release();
            target.release();
            copy.release();
            for (ByteBuf source : sources) {
                assertEquals(1, source.refCnt());
                source.release();
            }
        }
    }

    @Test
    public void rawRemovalReturnsIndependentEntryAndClearReleasesOtherSources() {
        ByteBuf source = input("Name:42", 0);
        HeaderEntryStore store = new HeaderEntryStore();
        DefaultHttpHeaderEntry removed = null;
        try {
            store.addDecoded(source, 0, 4, 5, 2);
            store.addDecoded(source, 0, 4, 5, 2);
            removed = store.remove(0);
            assertEquals(3, source.refCnt());
            store.clear();
            assertEquals(2, source.refCnt());
            assertEquals("42", removed.getValue());
            assertEquals("Name", removed.getName());
            assertEquals(1, source.refCnt());
        } finally {
            store.releaseEntries();
            store.clear();
            if (removed != null)
                removed.release();
            source.release();
        }
    }

    @Test
    public void recycledMetadataDoesNotRetainAnySource() throws Exception {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        List<ByteBuf> sources = new ArrayList<>();
        try {
            for (int i = 0; i < 65; i++) {
                ByteBuf source = input("Name:42", i % 3);
                sources.add(source);
                headers.addDecodedHeader(source, 0, 4, 5, 2);
                assertRetainedWeight(field(headers.headerEntries(), "storage"));
            }
            Object storage = field(headers.headerEntries(), "storage");
            headers.release();
            assertRetainedWeight(storage);
            assertNull(field(storage, "source"));
            for (Object source : (Object[]) field(storage, "extraSources"))
                assertNull(source);
            for (Object item : (Object[]) field(storage, "data"))
                assertNull(item);
            for (ByteBuf source : sources)
                assertEquals(1, source.refCnt());
        } finally {
            headers.release();
            sources.forEach(ByteBuf::release);
        }
    }

    @Test
    public void scannerKeepsConstantSharedViewCountForFourRawFields() {
        for (int kind = 0; kind < 3; kind++) {
            long active = ByteBufAllocator.DEFAULT.metric().totalActiveAllocations();
            long bytes = ByteBufAllocator.DEFAULT.metric().totalActiveBytes();
            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            ProtoQueue<ByteBuf> queue = new ProtoQueue<>(8);
            HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
            ByteBuf[] shared = new ByteBuf[1];
            try {
                queue.offerMessage(input("A:1\r\nB:2\r\nC:3\r\nD:4\r\n\r\n", kind));
                assertTrue(HttpHeaderStreamingScanner.scan(queue, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                    if (shared[0] == null)
                        shared[0] = line;
                    else
                        assertSame(shared[0], line);
                    headers.addDecodedHeader(line, ns, nl, vs, vl);
                    assertEquals(2, line.refCnt());
                }));
                assertEquals(1, shared[0].refCnt());
                assertEquals("3", headers.getString("C"));
                headers.release();
                assertTrue(shared[0].isFree());
            } finally {
                headers.release();
                queue.clearAndRelease();
                state.releaseAndReset();
            }
            assertEquals(active, ByteBufAllocator.DEFAULT.metric().totalActiveAllocations());
            assertEquals(bytes, ByteBufAllocator.DEFAULT.metric().totalActiveBytes());
        }
    }

    @Test
    public void callbackFailureAfterStoringRowKeepsContainerReferenceValid() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        ByteBuf[] shared = new ByteBuf[1];
        try {
            queue.offerMessage(input("A:1\r\nB:2\r\n\r\n", 2));
            try {
                HttpHeaderStreamingScanner.scan(queue, state, 512, (ctx, line, ns, nl, vs, vl) -> {
                    shared[0] = line;
                    headers.addDecodedHeader(line, ns, nl, vs, vl);
                    if (headers.headerSize() == 2)
                        throw new IllegalArgumentException("callback failed");
                });
                fail("Expected callback failure");
            } catch (IllegalArgumentException expected) {
                assertEquals("callback failed", expected.getMessage());
            }
            queue.clearAndRelease();
            assertEquals(1, shared[0].refCnt());
            assertEquals("1", headers.getString("A"));
            assertEquals("2", headers.getString("B"));
            headers.release();
            assertTrue(shared[0].isFree());
        } finally {
            headers.release();
            queue.clearAndRelease();
            state.releaseAndReset();
        }
    }

    @Test
    public void partialAndCompleteLinesReleaseEachBackingBlockIndependently() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(8);
        HttpContext.RequestDecodeState state = new HttpContext.RequestDecodeState();
        List<ByteBuf> views = new ArrayList<>();
        HttpHeaderStreamingScanner.HeaderLineConsumer<HttpContext.RequestDecodeState> consumer = (ctx, line, ns, nl, vs, vl) -> {
            views.add(line);
            headers.addDecodedHeader(line, ns, nl, vs, vl);
        };
        try {
            queue.offerMessage(input("A:1\r\nB:lo", 2));
            assertFalse(HttpHeaderStreamingScanner.scan(queue, state, 512, consumer));
            queue.offerMessage(input("ng\r\nC:3\r\n\r\n", 2));
            assertTrue(HttpHeaderStreamingScanner.scan(queue, state, 512, consumer));
            assertEquals(3, views.size());
            for (ByteBuf view : views)
                assertEquals(1, view.refCnt());
            headers.removeHeader("B");
            assertTrue(views.get(1).isFree());
            assertEquals("1", headers.getString("A"));
            assertEquals("3", headers.getString("C"));
            headers.release();
            for (ByteBuf view : views)
                assertTrue(view.isFree());
        } finally {
            headers.release();
            queue.clearAndRelease();
            state.releaseAndReset();
        }
    }
}
