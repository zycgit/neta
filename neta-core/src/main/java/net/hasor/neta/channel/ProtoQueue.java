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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Queue implementation that simultaneously implements {@link ProtoRcvQueue} and {@link ProtoSndQueue};
 * concrete capacity limits are controlled by the {@code capacity} parameter.
 * <p>One {@code ProtoQueue} instance serves as both the receive queue, the consumer side, and the
 * send queue, the producer side, at a pipeline stage boundary. That means the staging buffer
 * between upstream consumers and downstream producers is concentrated in a single object.</p>
 * <h3>Capacity and overflow</h3>
 * Capacity is set at construction time. When {@code capacity < 0}, the queue behaves as unbounded,
 * {@link Integer#MAX_VALUE}. Multi-element {@link #offerMessage} calls are atomic: the entire batch
 * is either accepted or rejected with {@code false} and no queue-state change.
 * When capacity is exhausted, {@link #offerMessage} only returns {@code false}. Whether that write
 * failure should be upgraded to {@link ProtoFullException} is determined by outer pipeline scheduling code.
 * <h3>Ownership semantics</h3>
 * Both directions use immediate-effect semantics: once a send-side {@link #offerMessage} succeeds,
 * it becomes visible immediately, and receive-side reads or destructive skips affect the queue at once.
 * <p>Ownership rules are:</p>
 * <ul>
 *   <li>After successful {@code offerMessage(...)}, ownership transfers to the queue.</li>
 *   <li>After {@code takeMessage(...)}, ownership transfers to the caller.</li>
 *   <li>{@code peekMessage(...)} is read-only and does not transfer ownership.</li>
 *   <li>{@code skipMessage(...)} discards objects still owned by the queue and releases or closes them when applicable.</li>
 * </ul>
 * @param <T> message type stored in the queue
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see ProtoRcvQueue
 * @see ProtoSndQueue
 */
public class ProtoQueue<T> implements ProtoRcvQueue<T>, ProtoSndQueue<T> {
    private static final Logger        logger      = Logger.getLogger(ProtoQueue.class);
    private static final Object[]      EMPTY_ARRAY = new Object[0];
    /** Standard immutable empty {@link ProtoRcvQueue} singleton with no ownership obligations. */
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
     * Create a queue with the given capacity.
     * @param capacity maximum number of queued messages at once; a negative value means unbounded, {@link Integer#MAX_VALUE}
     */
    public ProtoQueue(int capacity) {
        this.capacity = capacity < 0 ? Integer.MAX_VALUE : capacity;
        this.linkedList = new ArrayList<>();
    }

    /**
     * Return an immutable empty {@link ProtoRcvQueue} singleton.
     * <p>This is useful when a non-null queue reference is needed but no data is currently available,
     * for example when invoking a routing predicate during the {@code onActive} phase.</p>
     * <p>The returned queue never owns any messages, and all destructive operations are no-ops.</p>
     */
    public static <T> ProtoRcvQueue<T> emptyRcv() {
        return (ProtoRcvQueue<T>) EMPTY_RCV;
    }

    /** {@inheritDoc} */
    @Override
    public int getCapacity() {
        return this.capacity;
    }

    /** {@inheritDoc} */
    @Override
    public int queueSize() {
        return this.linkedList.size();
    }

    /** {@inheritDoc} */
    @Override
    public int slotSize() {
        return this.capacity - this.linkedList.size();
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(T[] offerList) {
        if (offerList == null || offerList.length == 0) {
            return false;
        }

        if (this.slotSize() < offerList.length) {
            return false;
        }

        this.linkedList.addAll(Arrays.asList(offerList));
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(List<T> offerList) {
        if (offerList == null || offerList.isEmpty()) {
            return false;
        }

        int size = offerList.size();
        if (this.slotSize() < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            this.linkedList.add(offerList.get(i));
        }
        return true;
    }

    /** Single-element fast path that avoids allocating {@link Collections#singletonList(Object)}. */
    @Override
    public boolean offerMessage(T offerMessage) {
        if (this.slotSize() <= 0) {
            return false;
        }

        this.linkedList.add(offerMessage);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(ProtoRcvQueue<T> offerList) {
        if (offerList == null) {
            return false;
        }

        int size = offerList.queueSize();
        if (size <= 0 || this.slotSize() < size) {
            return false;
        }

        return this.offerMessage(offerList.takeMessage(size));
    }

    /** Single-element fast path that avoids allocating {@link ArrayList} and transfers ownership immediately. */
    @Override
    public T takeMessage() {
        if (this.linkedList.isEmpty()) {
            return null;
        }

        return this.linkedList.remove(0);
    }

    /** {@inheritDoc} */
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

    /** Single-element fast path that avoids allocating {@link ArrayList} and does not transfer ownership. */
    @Override
    public T peekMessage() {
        if (this.linkedList.isEmpty()) {
            return null;
        }

        return this.linkedList.get(0);
    }

    /** {@inheritDoc} */
    @Override
    public List<T> peekMessage(int cnt) {
        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        return new ArrayList<>(this.linkedList.subList(0, fixCnt));
    }

    /** {@inheritDoc} */
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
     * Release and remove all elements still left in the queue and still owned by it.
     * <p>This is mainly used as the final cleanup step in the protocol-stack close path. Elements
     * previously returned through {@link #takeMessage()} are excluded because their ownership has
     * already been transferred away.</p>
     */
    void clearAndClose() {
        for (Object item : this.linkedList) {
            releaseOwned(item);
        }
        this.linkedList.clear();
    }

    /**
     * Release an element when the queue discards something it still owns.
     * <p>{@link ReferenceHolder} instances are released through reference counting. Ordinary
     * {@link Closeable} objects are closed quietly. Other object types are left unchanged.</p>
     */
    private static void releaseOwned(Object item) {
        try {
            if (item instanceof ReferenceHolder) {
                ((ReferenceHolder) item).release();
            } else if (item instanceof Release) {
                ((Release) item).release();
            } else if (item instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) item);
            }
        } catch (Throwable e) {
            logger.error("ProtoQueue releaseOwned failed: " + e.getMessage(), e);
        }
    }

    /**
     * Take up to {@code cnt} messages and return them directly as a raw array.
     * <p>This is a performance-oriented bulk-transfer helper used by the protocol stack to avoid
     * intermediate {@link List} allocation. As with {@link #takeMessage(int)}, ownership transfers
     * to the caller after return.</p>
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

    /**
     * Return the monitoring string for the current queue.
     * @return string containing capacity, queue length, and remaining slot count
     */
    @Override
    public String toString() {
        if (Integer.MAX_VALUE == this.capacity) {
            return "Queue@" + Integer.toHexString(hashCode()) + ", capacity:INT_MAX_VALUE, queueSize:" + this.queueSize() + ", slotSize:" + this.slotSize();
        } else {
            return "Queue@" + Integer.toHexString(hashCode()) + ", capacity:" + capacity + ", queueSize:" + this.queueSize() + ", slotSize:" + this.slotSize();
        }
    }
}