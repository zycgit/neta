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
package net.hasor.neta.channel.data;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.function.Release;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class ProtoQueueTest {
    @Test
    public void singleViewPreservesSharedCapacityAndLazyReattachment() {
        ProtoQueue<Integer> queue = new ProtoQueue<>(3);
        ProtoSndQueueView<Integer> lazy = queue.subQueue("stage");
        assertTrue(queue.queueNames().isEmpty());
        assertTrue(queue.offerMessage(Arrays.asList(1, 2, 3)));
        queue.drainToQueue(new String("stage"), 2);
        ProtoRcvQueueView<Integer> first = queue.queueView("stage");
        assertEquals(0, queue.slotSize());
        assertEquals(0, lazy.slotSize());
        List<String> names = queue.queueNames();
        names.clear();
        assertTrue(queue.hasQueue("stage"));
        assertEquals(Integer.valueOf(1), first.takeMessage());
        assertEquals(1, queue.slotSize());
        assertTrue(lazy.offerMessage(4));
        assertEquals(0, queue.slotSize());
        first.returnToHead();
        assertFalse(queue.hasQueue("stage"));
        assertEquals(Arrays.asList(2, 4, 3), queue.takeMessage(-1));
        assertEquals(3, queue.slotSize());
        assertTrue(lazy.offerMessage(5));
        assertNotSame(first, queue.queueView("stage"));
        first.discard();
        assertEquals(Integer.valueOf(5), queue.queueView("stage").takeMessage());
        assertEquals(3, queue.slotSize());
        assertTrue(queue.queueNames().isEmpty());
    }

    @Test
    public void multipleViewsPreserveIdentityAndInsertionOrder() {
        ProtoQueue<Integer> queue = new ProtoQueue<>(4);
        assertTrue(queue.offerMessage(Arrays.asList(1, 2, 3, 4)));
        queue.drainToQueue(" a ", 2);
        ProtoRcvQueueView<Integer> first = queue.queueView(" a ");
        queue.drainToQueue("b", 1);
        assertSame(first, queue.queueView(new String(" a ")));
        assertEquals(Arrays.asList(" a ", "b"), queue.queueNames());
        assertFalse(queue.hasQueue("a"));
        assertEquals(0, queue.slotSize());
        assertEquals(Arrays.asList(1, 2), first.takeMessage(-1));
        queue.drainToQueue(" a ", 1);
        assertEquals(Arrays.asList("b", " a "), queue.queueNames());
        first.discard();
        assertEquals(Integer.valueOf(4), queue.queueView(" a ").peekMessage());
        queue.queueView("b").returnToTail();
        assertEquals(Integer.valueOf(3), queue.takeMessage());
        assertEquals(Integer.valueOf(4), queue.queueView(" a ").takeMessage());
        assertTrue(queue.queueNames().isEmpty());
        assertEquals(4, queue.slotSize());
    }

    @Test
    public void clearReleasesSingleAndMultipleViewsExactlyOnce() {
        for (boolean multiple : new boolean[] { false, true }) {
            ProtoQueue<Release> queue = new ProtoQueue<>(5);
            AtomicInteger releases = new AtomicInteger();
            for (int i = 0; i < 5; i++) {
                assertTrue(queue.offerMessage((Release) releases::incrementAndGet));
            }
            queue.drainToQueue("first", 2);
            ProtoRcvQueueView<Release> stale = queue.queueView("first");
            if (multiple) {
                queue.drainToQueue("second", 2);
            }
            queue.clearAndRelease();
            assertEquals(5, releases.get());
            assertEquals(5, queue.slotSize());
            assertTrue(queue.queueNames().isEmpty());
            assertTrue(queue.subQueue("first").offerMessage((Release) releases::incrementAndGet));
            stale.discard();
            assertEquals(5, releases.get());
            queue.clearAndRelease();
            queue.clearAndRelease();
            assertEquals(6, releases.get());
            assertEquals(5, queue.slotSize());
        }
    }

    @Test
    public void offerTest01() {
        ProtoQueue<Object> queue = new ProtoQueue<>(10);
        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;

        assert queue.offerMessage(1);
        assert queue.offerMessage(2);
        assert queue.offerMessage(3);
        assert queue.queueSize() == 3;
        assert queue.slotSize() == 7;

        assert queue.offerMessage(4);
        assert queue.offerMessage(Arrays.asList(5, 6));
        assert queue.queueSize() == 6;
        assert queue.slotSize() == 4;

        assert queue.offerMessage(Arrays.asList(7, 8));
        assert queue.queueSize() == 8;
        assert queue.slotSize() == 2;

        assert !queue.offerMessage(Arrays.asList(10, 11, 12, 13, 14, 15, 16, 17, 18, 19));
        assert queue.queueSize() == 8;
        assert queue.slotSize() == 2;

        assert queue.offerMessage(Arrays.asList(10, 11));
        assert queue.queueSize() == 10;
        assert queue.slotSize() == 0;

        List<Object> list = queue.takeMessage(100);
        assert list.size() == 10;
        assert (int) list.get(0) == 1;
        assert (int) list.get(1) == 2;
        assert (int) list.get(2) == 3;
        assert (int) list.get(3) == 4;
        assert (int) list.get(4) == 5;
        assert (int) list.get(5) == 6;
        assert (int) list.get(6) == 7;
        assert (int) list.get(7) == 8;
        assert (int) list.get(8) == 10;
        assert (int) list.get(9) == 11;

        assert queue.queueSize() == 0;
        assert queue.slotSize() == 10;
    }
}
