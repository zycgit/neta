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
package net.hasor.neta.channel;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Concrete, capacity-bounded implementation of both {@link ProtoRcvQueue} and
 * {@link ProtoSndQueue}.
 * <p>A single {@code ProtoQueue} instance serves simultaneously as the receive queue
 * (consumer side) and the send queue (producer side) for one pipeline stage boundary,
 * so the staging buffer between the upstream consumer and the downstream producer is
 * contained in a single object.
 * <h3>Capacity and overflow</h3>
 * Capacity is set at construction time.  If {@code capacity < 0} the queue is effectively
 * unbounded ({@link Integer#MAX_VALUE}). Multi-element {@link #offerMessage} calls are
 * atomic: the whole batch is accepted or the call returns {@code 0} without changing queue
 * state. Once all capacity is consumed, {@link #offerMessage} returns {@code 0}
 * (no items accepted), and the pipeline send path raises {@link ProtoFullException} as a
 * backpressure signal.
 * <h3>Ownership semantics</h3>
 * Both directions are immediate: send-side writes become visible as soon as an
 * {@link #offerMessage} call succeeds, and receive-side reads/destructive skips consume the
 * queue immediately.
 * <p>Ownership rules are:</p>
 * <ul>
 *   <li>{@code offerMessage(...)} success means ownership moves into the queue.</li>
 *   <li>{@code takeMessage(...)} means ownership moves out of the queue to the caller.</li>
 *   <li>{@code peekMessage(...)} never transfers ownership.</li>
 *   <li>{@code skipMessage(...)} discards queue-owned objects and releases/close them when applicable.</li>
 * </ul>
 * @param <T> the type of message stored in this queue
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoRcvQueue
 * @see ProtoSndQueue
 * @see ProtoFullException
 */
public class ProtoQueue<T> implements ProtoRcvQueue<T>, ProtoSndQueue<T> {
    private static final Object[]      EMPTY_ARRAY = new Object[0];
    /** Canonical immutable empty {@link ProtoRcvQueue} singleton with no ownership obligations. */
    @SuppressWarnings("rawtypes")
    private static final ProtoRcvQueue EMPTY_RCV   = new ProtoRcvQueue() {
        @Override
        public int getCapacity() {
            return 0;
        }

        @Override
        public int queueSize() {
            return 0;
        }

        @Override
        public List takeMessage(int cnt) {
            return Collections.emptyList();
        }

        @Override
        public List peekMessage(int cnt) {
            return Collections.emptyList();
        }

        @Override
        public void skipMessage(int cnt) {
        }
    };

    private final int     capacity;
    private final List<T> linkedList;

    /**
     * Creates a queue with the given capacity.
     * @param capacity max number of simultaneously queued messages; negative means unbounded ({@link Integer#MAX_VALUE})
     */
    public ProtoQueue(int capacity) {
        this.capacity = capacity < 0 ? Integer.MAX_VALUE : capacity;
        this.linkedList = new ArrayList<>();
    }

    /**
     * Returns an immutable empty {@link ProtoRcvQueue} singleton.
     * <p>Useful when a non-null queue reference is required but no data is available,
     * e.g. when invoking a routing predicate during the {@code onActive} phase.</p>
     * <p>The returned queue never owns any message and all destructive operations are no-ops.</p>
     */
    public static <T> ProtoRcvQueue<T> emptyRcv() {
        return (ProtoRcvQueue<T>) EMPTY_RCV;
    }

    @Override
    public int getCapacity() {
        return this.capacity;
    }

    @Override
    public int queueSize() {
        return this.linkedList.size();
    }

    @Override
    public int slotSize() {
        return this.capacity - this.linkedList.size();
    }

    @Override
    public int offerMessage(T[] offerList) {
        if (offerList == null || offerList.length == 0) {
            return 0;
        }

        if (this.slotSize() < offerList.length) {
            return 0;
        }

        this.linkedList.addAll(Arrays.asList(offerList));
        return offerList.length;
    }

    @Override
    public int offerMessage(List<T> offerList) {
        if (offerList == null || offerList.isEmpty()) {
            return 0;
        }

        int size = offerList.size();
        if (this.slotSize() < size) {
            return 0;
        }

        for (int i = 0; i < size; i++) {
            this.linkedList.add(offerList.get(i));
        }
        return size;
    }

    /** Single-element fast path: avoids {@link Collections#singletonList(Object)} allocation. */
    @Override
    public boolean offerMessage(T offerMessage) {
        if (this.slotSize() <= 0) {
            return false;
        }

        this.linkedList.add(offerMessage);
        return true;
    }

    @Override
    public int offerMessage(ProtoRcvQueue<T> offerList) {
        if (offerList == null) {
            return 0;
        }

        int size = offerList.queueSize();
        if (size <= 0 || this.slotSize() < size) {
            return 0;
        }

        return this.offerMessage(offerList.takeMessage(size));
    }

    /** Single-element fast path: avoids {@link ArrayList} allocation and transfers ownership immediately. */
    @Override
    public T takeMessage() {
        if (this.linkedList.isEmpty()) {
            return null;
        }

        return this.linkedList.remove(0);
    }

    @Override
    public List<T> takeMessage(int cnt) {
        if (cnt == 0) {
            return Collections.emptyList();
        }

        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt == 0) {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>(this.linkedList.subList(0, fixCnt));
        this.linkedList.subList(0, fixCnt).clear();
        return result;
    }

    /** Single-element fast path: avoids {@link ArrayList} allocation without transferring ownership. */
    @Override
    public T peekMessage() {
        if (this.linkedList.isEmpty()) {
            return null;
        }

        return this.linkedList.get(0);
    }

    @Override
    public List<T> peekMessage(int cnt) {
        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        return new ArrayList<>(this.linkedList.subList(0, fixCnt));
    }

    @Override
    public void skipMessage(int cnt) {
        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt > 0) {
            for (int i = 0; i < fixCnt; i++) {
                releaseOwned(this.linkedList.get(i));
            }
            this.linkedList.subList(0, fixCnt).clear();
        }
    }

    /**
     * Releases and removes all queue-owned items that still remain in this queue.
     * <p>This is primarily used by the protocol stack close path as a final cleanup step.
     * Items previously returned by {@link #takeMessage()} are not part of this cleanup because
     * ownership has already been transferred to the caller.</p>
     */
    void clearAndClose() {
        for (Object item : this.linkedList) {
            releaseOwned(item);
        }
        this.linkedList.clear();
    }

    /**
     * Releases a queue-owned item when the queue discards it.
     * <p>{@link ReferenceHolder} is released via reference counting. Plain {@link Closeable}
     * objects are closed quietly. All other object types are left untouched.</p>
     */
    private static void releaseOwned(Object item) {
        if (item instanceof ReferenceHolder) {
            ((ReferenceHolder) item).release();
        } else if (item instanceof Release) {
            ((Release) item).release();
        } else if (item instanceof Closeable) {
            IOUtils.closeQuietly((Closeable) item);
        }
    }

    /**
     * Takes up to {@code cnt} messages and returns them as a raw array.
     * <p>This is a performance-oriented bulk-transfer helper used by the protocol stack to avoid
     * intermediate list allocation. As with {@link #takeMessage(int)}, ownership transfers to the caller.</p>
     */
    public Object[] takeMessageToArray(int cnt) {
        if (cnt <= 0) {
            return EMPTY_ARRAY;
        }
        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt == 0) {
            return EMPTY_ARRAY;
        }
        Object[] result = new Object[fixCnt];
        for (int i = 0; i < fixCnt; i++) {
            result[i] = this.linkedList.get(i);
        }
        this.linkedList.subList(0, fixCnt).clear();
        return result;
    }

    @Override
    public String toString() {
        if (Integer.MAX_VALUE == this.capacity) {
            return "Queue@" + Integer.toHexString(hashCode()) + ", capacity:INT_MAX_VALUE, queueSize:" + this.queueSize() + ", slotSize:" + this.slotSize();
        } else {
            return "Queue@" + Integer.toHexString(hashCode()) + ", capacity:" + capacity + ", queueSize:" + this.queueSize() + ", slotSize:" + this.slotSize();
        }
    }
}