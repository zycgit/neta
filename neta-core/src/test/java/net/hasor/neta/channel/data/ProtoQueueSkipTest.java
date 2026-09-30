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

public class ProtoQueueSkipTest {
    @Test
    public void skipCountsPreserveNullsOrderAndSharedCapacity() {
        for (boolean sub : new boolean[] { false, true }) {
            for (int count : new int[] { -1, 0, 1, 2, 3, 4, 99 }) {
                Fixture fixture = new Fixture(sub, 8);
                List<Integer> released = new ArrayList<>();
                Release first = () -> released.add(1);
                Release second = () -> released.add(2);
                Release third = () -> released.add(3);
                List<Release> values = Arrays.asList(first, null, second, third);
                assertTrue(fixture.writer.offerMessage(values));
                fixture.reader.skipMessage(count);
                int skipped = Math.max(0, Math.min(count, values.size()));
                assertEquals(values.subList(skipped, values.size()), fixture.reader.peekMessage(-1));
                assertEquals(4 + skipped, fixture.owner.slotSize());
                assertEquals(skipped > 1 ? skipped - 1 : skipped, released.size());
                if (sub) {
                    assertEquals(skipped != 4, fixture.owner.hasQueue("pending"));
                }
                fixture.owner.clearAndRelease();
                assertEquals(Arrays.asList(1, 2, 3), released);
                assertEquals(8, fixture.owner.slotSize());
            }
        }
    }

    @Test
    public void everyReleaseObservesTheWholePrefixBeforeRemoval() {
        for (boolean sub : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(sub, 4);
            List<Integer> sizes = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            List<Release> heads = new ArrayList<>();
            Release value = () -> {
                sizes.add(fixture.reader.queueSize());
                slots.add(fixture.owner.slotSize());
                heads.add(fixture.reader.peekMessage());
            };
            assertTrue(fixture.writer.offerMessage(Arrays.asList(value, value, value)));
            fixture.reader.skipMessage(3);
            assertEquals(Arrays.asList(3, 3, 3), sizes);
            assertEquals(Arrays.asList(1, 1, 1), slots);
            assertEquals(Arrays.asList(value, value, value), heads);
            assertEquals(0, fixture.reader.queueSize());
            assertEquals(4, fixture.owner.slotSize());
        }
    }

    @Test
    public void releaseAppendedMessagesSurviveSingleAndWholePrefixRemoval() {
        for (boolean sub : new boolean[] { false, true }) {
            for (int count : new int[] { 1, 2, 5 }) {
                Fixture fixture = new Fixture(sub, count + 1);
                AtomicInteger originalReleased = new AtomicInteger();
                AtomicInteger appendedReleased = new AtomicInteger();
                Release appended = appendedReleased::incrementAndGet;
                List<Boolean> accepted = new ArrayList<>();
                Release first = () -> {
                    originalReleased.incrementAndGet();
                    accepted.add(fixture.writer.offerMessage(appended));
                };
                assertTrue(fixture.writer.offerMessage(first));
                for (int i = 1; i < count; i++) {
                    assertTrue(fixture.writer.offerMessage((Release) originalReleased::incrementAndGet));
                }
                fixture.reader.skipMessage(count);
                assertEquals(Arrays.asList(true), accepted);
                assertEquals(count, originalReleased.get());
                assertEquals(0, appendedReleased.get());
                assertEquals(1, fixture.reader.queueSize());
                assertSame(appended, fixture.reader.peekMessage());
                assertEquals(count, fixture.owner.slotSize());
                if (sub) {
                    assertTrue(fixture.owner.hasQueue("pending"));
                    assertSame(fixture.reader, fixture.owner.queueView("pending"));
                }
                fixture.reader.skipMessage(1);
                assertEquals(1, appendedReleased.get());
                assertEquals(count + 1, fixture.owner.slotSize());
            }
        }
    }

    @Test
    public void capacityIsNotReturnedUntilReleaseCallbacksComplete() {
        for (boolean sub : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(sub, 1);
            AtomicInteger rejectedReleased = new AtomicInteger();
            Release rejected = rejectedReleased::incrementAndGet;
            List<Boolean> accepted = new ArrayList<>();
            assertTrue(fixture.writer.offerMessage((Release) () -> accepted.add(fixture.writer.offerMessage(rejected))));
            fixture.reader.skipMessage(1);
            assertEquals(Arrays.asList(false), accepted);
            assertEquals(1, fixture.owner.slotSize());
            assertEquals(0, rejectedReleased.get());
            rejected.release();
            assertEquals(1, rejectedReleased.get());
        }
    }

    @Test
    public void releaseFailureStillReleasesRemainingMessagesAndRestoresCapacity() {
        for (boolean sub : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(sub, 3);
            AtomicInteger released = new AtomicInteger();
            Release failure = () -> {
                released.incrementAndGet();
                throw new IllegalStateException("expected release failure");
            };
            assertTrue(fixture.writer.offerMessage(Arrays.asList(failure, null, released::incrementAndGet)));
            fixture.reader.skipMessage(3);
            assertEquals(2, released.get());
            assertEquals(0, fixture.reader.queueSize());
            assertEquals(3, fixture.owner.slotSize());
            assertFalse(fixture.owner.hasQueue("pending"));
        }
    }

    @Test
    public void closedViewCannotConsumeNewSameNamedOrSiblingQueues() {
        ProtoQueue<Release> queue = new ProtoQueue<>(4);
        AtomicInteger released = new AtomicInteger();
        Release value = released::incrementAndGet;
        assertTrue(queue.subQueue("pending").offerMessage(value));
        ProtoRcvQueueView<Release> closed = queue.queueView("pending");
        closed.skipMessage(1);
        assertTrue(queue.subQueue("pending").offerMessage(value));
        assertTrue(queue.subQueue("sibling").offerMessage(value));
        ProtoRcvQueueView<Release> replacement = queue.queueView("pending");
        assertNotSame(closed, replacement);
        closed.skipMessage(Integer.MAX_VALUE);
        assertEquals(1, released.get());
        assertEquals(2, queue.slotSize());
        assertSame(value, replacement.peekMessage());
        assertSame(value, queue.queueView("sibling").peekMessage());
        replacement.skipMessage(1);
        assertFalse(queue.hasQueue("pending"));
        assertTrue(queue.hasQueue("sibling"));
        queue.clearAndRelease();
        assertEquals(3, released.get());
        assertEquals(4, queue.slotSize());
    }

    private static class Fixture {
        final ProtoQueue<Release>   owner;
        final ProtoRcvData<Release> reader;
        final ProtoSndData<Release> writer;

        Fixture(boolean sub, int capacity) {
            this.owner = new ProtoQueue<>(capacity);
            this.writer = sub ? this.owner.subQueue("pending") : this.owner;
            this.reader = sub ? this.owner.queueView("pending") : this.owner;
        }
    }
}
