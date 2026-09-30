/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.data;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.function.Release;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtoQueueCursorTest {
    @Test
    public void repeatedFifoConsumptionPreservesOrderAndSharedCapacity() {
        ProtoQueue<Integer> queue = new ProtoQueue<>(129);
        for (int i = 0; i < 129; i++) {
            assertTrue(queue.offerMessage(i));
        }
        for (int i = 0; i < 4096; i++) {
            assertEquals(0, queue.slotSize());
            assertEquals(Integer.valueOf(i), queue.takeMessage());
            assertEquals(1, queue.slotSize());
            assertTrue(queue.offerMessage(i + 129));
        }
        Object[] remaining = queue.takeMessageToArray(-1);
        assertEquals(129, remaining.length);
        for (int i = 0; i < remaining.length; i++) {
            assertEquals(4096 + i, remaining[i]);
        }
        assertEquals(129, queue.slotSize());
    }

    @Test
    public void batchOverflowDoesNotConsumeSourceOrChangeDestination() {
        ProtoQueue<Integer> source = new ProtoQueue<>(10);
        ProtoQueue<Integer> target = new ProtoQueue<>(4);
        assertTrue(source.offerMessage(Arrays.asList(5, null, 6)));
        assertTrue(target.offerMessage(Arrays.asList(0, 1, 2, 3)));
        assertEquals(Integer.valueOf(0), target.takeMessage());
        assertFalse(target.offerMessage(source));
        assertEquals(Arrays.asList(5, null, 6), source.peekMessage(-1));
        assertEquals(Arrays.asList(1, 2, 3), target.peekMessage(-1));
        target.skipMessage(2);
        assertTrue(target.offerMessage(source));
        assertFalse(source.hasMore());
        assertEquals(Arrays.asList(3, 5, null, 6), target.takeMessage(-1));
    }

    @Test
    public void consumedMainAndSubqueuePrefixesCanBeReturnedToEitherEnd() {
        for (boolean toHead : new boolean[] { false, true }) {
            ProtoQueue<Integer> queue = new ProtoQueue<>(300);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                all.add(i);
            }
            queue.offerMessage(all);
            queue.takeMessage(31);
            queue.drainToQueue("pending", 160);
            ProtoRcvQueueView<Integer> pending = queue.queueView("pending");
            assertEquals(new ArrayList<>(all.subList(31, 111)), pending.takeMessage(80));
            assertEquals(155, queue.slotSize());
            List<Integer> expected = new ArrayList<>();
            if (toHead) {
                pending.returnToHead();
                expected.addAll(all.subList(111, 256));
            } else {
                pending.returnToTail();
                expected.addAll(all.subList(191, 256));
                expected.addAll(all.subList(111, 191));
            }
            assertFalse(queue.hasQueue("pending"));
            assertEquals(expected, queue.takeMessage(-1));
            assertEquals(300, queue.slotSize());
        }
    }

    @Test
    public void predicateTransferPreservesOrderAcrossCompaction() {
        ProtoQueue<Integer> queue = new ProtoQueue<>(300);
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            values.add(i);
        }
        queue.offerMessage(values);
        queue.takeMessage(53);
        queue.drainToQueue("even", -1, value -> value % 2 == 0);
        List<Integer> even = new ArrayList<>(), odd = new ArrayList<>();
        for (int i = 53; i < 200; i++) {
            (i % 2 == 0 ? even : odd).add(i);
        }
        assertEquals(153, queue.slotSize());
        assertEquals(odd, queue.takeMessage(-1));
        assertEquals(even, queue.queueView("even").takeMessage(-1));
        assertEquals(300, queue.slotSize());
    }

    @Test
    public void singleNullTransferStillOwnsOneSlot() {
        ProtoQueue<Integer> queue = new ProtoQueue<>(2);
        queue.offerMessage(Arrays.asList(null, 7));
        queue.drainToQueue("nullable", 1);
        assertEquals(0, queue.slotSize());
        ProtoRcvQueueView<Integer> view = queue.queueView("nullable");
        assertEquals(1, view.queueSize());
        assertNull(view.takeMessage());
        assertFalse(queue.hasQueue("nullable"));
        assertEquals(1, queue.slotSize());
        assertEquals(Integer.valueOf(7), queue.takeMessage());
        assertEquals(2, queue.slotSize());
    }

    @Test
    public void partialAndFullArrayTransfersPreserveOffsetsNullsAndOwnership() {
        ProtoQueue<Release> queue = new ProtoQueue<>(10);
        AtomicInteger released = new AtomicInteger();
        Release first = released::incrementAndGet;
        Release second = released::incrementAndGet;
        Release third = released::incrementAndGet;
        assertTrue(queue.offerMessage(Arrays.asList(first, null, second, third)));
        assertArrayEquals(new Object[] { first }, queue.takeMessageToArray(1));
        queue.drainToQueue("pending", 2);
        ProtoRcvQueueView<Release> pending = queue.queueView("pending");
        assertArrayEquals(new Object[] { null }, pending.takeMessageToArray(1));
        assertArrayEquals(new Object[] { second }, pending.takeMessageToArray(-1));
        assertFalse(queue.hasQueue("pending"));
        assertEquals(9, queue.slotSize());
        assertArrayEquals(new Object[] { third }, queue.takeMessageToArray(10));
        assertEquals(0, queue.takeMessageToArray(-1).length);
        assertEquals(0, pending.takeMessageToArray(-1).length);
        assertEquals(10, queue.slotSize());
        queue.clearAndRelease();
        assertEquals(0, released.get());
        first.release();
        second.release();
        third.release();
        assertEquals(3, released.get());
    }

    @Test
    public void compactionNeverReleasesTransferredObjectsAndCleanupReleasesOwnedOnesOnce() {
        ProtoQueue<Release> queue = new ProtoQueue<>(200);
        AtomicInteger released = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            queue.offerMessage((Release) released::incrementAndGet);
        }
        List<Release> transferred = queue.takeMessage(73);
        assertEquals(0, released.get());
        queue.drainToQueue("pending", 80);
        queue.queueView("pending").skipMessage(65);
        assertEquals(65, released.get());
        queue.clearAndRelease();
        assertEquals(127, released.get());
        for (Release value : transferred) {
            value.release();
        }
        assertEquals(200, released.get());
        assertEquals(200, queue.slotSize());
    }
}
