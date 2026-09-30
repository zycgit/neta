/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.function.Release;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtoQueueCursorBackpressureTest {
    @Test
    public void skipPublishesWritableAfterReleaseAndAllowsCallbackRefill() {
        ProtoQueueView queue = new ProtoQueueView(1);
        AtomicInteger recovered = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        int[] observed = new int[3];
        boolean[] accepted = new boolean[1];
        queue.onRecoveredWritable(() -> {
            recovered.incrementAndGet();
            accepted[0] = queue.offerMessage(7);
        });
        assertTrue(queue.offerMessage((Release) () -> {
            released.incrementAndGet();
            observed[0] = recovered.get();
            observed[1] = queue.queueSize();
            observed[2] = queue.slotSize();
        }));
        queue.skipMessage(1);
        assertArrayEquals(new int[] { 0, 1, 0 }, observed);
        assertEquals(1, released.get());
        assertEquals(1, recovered.get());
        assertTrue(accepted[0]);
        assertEquals(0, queue.slotSize());
        assertEquals(7, queue.peekMessage());
        queue.skipMessage(0);
        queue.skipMessage(-1);
        assertEquals(1, recovered.get());
    }

    @Test
    public void crossingCompactionBoundaryKeepsWritableTransitionsExact() {
        ProtoQueueView queue = new ProtoQueueView(129);
        AtomicInteger recovered = new AtomicInteger();
        queue.onRecoveredWritable(recovered::incrementAndGet);
        for (int i = 0; i < 129; i++) {
            assertTrue(queue.offerMessage(i));
        }
        for (int i = 0; i < 1024; i++) {
            assertEquals(i, queue.takeMessage());
            assertEquals(i + 1, recovered.get());
            assertTrue(queue.offerMessage(i + 129));
        }
        queue.skipMessage(65);
        assertEquals(1025, recovered.get());
        queue.skipMessage(64);
        assertEquals(1025, recovered.get());
        assertEquals(129, queue.slotSize());
    }
}
