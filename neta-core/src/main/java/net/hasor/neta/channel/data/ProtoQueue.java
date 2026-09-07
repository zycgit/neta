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
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.hasor.neta.channel.ProtoFullException;
import net.hasor.neta.channel.SoUtils;

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

        @Override
        public void drainToQueue(String key, int cnt) {
        }

        @Override
        public void drainToQueue(String key, int cnt, Predicate predicate) {
        }

        @Override
        public List<String> queueNames() {
            return Collections.emptyList();
        }

        @Override
        public boolean hasQueue(String key) {
            return false;
        }

        @Override
        public void discard(String key) {
        }

        @Override
        public ProtoRcvQueueView queueView(String key) {
            throw new UnsupportedOperationException("empty receive queue does not support queue views.");
        }
    };

    private final int                                   capacity;
    private final List<T>                               linkedList;
    private       ProtoQueueSndSubQueue<T>              singleSubQueue;
    private       Map<String, ProtoQueueSndSubQueue<T>> subQueueMap;
    private       int                                   totalOwned;

    /**
     * Create a queue with the given capacity.
     * @param capacity maximum number of queued messages at once; a negative value means unbounded, {@link Integer#MAX_VALUE}
     */
    public ProtoQueue(int capacity) {
        this.capacity = capacity < 0 ? Integer.MAX_VALUE : capacity;
        this.linkedList = new ArrayList<>();
        this.totalOwned = 0;
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
        return Math.max(0, this.capacity - this.totalOwned);
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(T[] offerList) {
        if (offerList == null) {
            return false;
        }

        if (offerList.length == 0) {
            return true;
        }

        if (this.slotSize() < offerList.length) {
            return false;
        }

        Collections.addAll(this.linkedList, offerList);
        this.totalOwned += offerList.length;
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(List<T> offerList) {
        if (offerList == null) {
            return false;
        }

        if (offerList.isEmpty()) {
            return true;
        }

        int size = offerList.size();
        if (this.slotSize() < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            this.linkedList.add(offerList.get(i));
        }
        this.totalOwned += size;
        return true;
    }

    /** Single-element fast path that avoids allocating {@link Collections#singletonList(Object)}. */
    @Override
    public boolean offerMessage(T offerMessage) {
        if (this.slotSize() <= 0) {
            return false;
        }

        this.linkedList.add(offerMessage);
        this.totalOwned++;
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean offerMessage(ProtoRcvQueue<T> offerList) {
        if (offerList == null) {
            return false;
        }

        if (offerList instanceof ProtoQueueRcvSubQueue) {
            ProtoQueueRcvSubQueue<T> subQueue = (ProtoQueueRcvSubQueue<T>) offerList;
            if (subQueue.owner() == this) {
                subQueue.moveAllToMainTail();
                return true;
            }
        }

        int size = offerList.queueSize();
        if (size == 0) {
            return true;
        }

        if (this.slotSize() < size) {
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

        T item = this.linkedList.remove(0);
        this.totalOwned--;
        return item;
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
        this.totalOwned -= fixCnt;
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

    @Override
    public void peekEachMessage(Consumer<? super T> consumer) {
        if (consumer == null || this.linkedList.isEmpty()) {
            return;
        }
        for (T item : this.linkedList) {
            consumer.accept(item);
        }
    }

    @Override
    public void peekEachMessage(int startIndex, Consumer<? super T> consumer) {
        if (consumer == null || this.linkedList.isEmpty()) {
            return;
        }
        int begin = Math.max(0, startIndex);
        for (int i = begin; i < this.linkedList.size(); i++) {
            consumer.accept(this.linkedList.get(i));
        }
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
                SoUtils.release(this.linkedList.get(i));
            }
            this.linkedList.subList(0, fixCnt).clear();
            this.totalOwned -= fixCnt;
        }
    }

    /**
     * Release and remove all elements still left in the queue and still owned by it.
     * <p>This is mainly used as the final cleanup step in the protocol-stack close path. Elements
     * previously returned through {@link #takeMessage()} are excluded because their ownership has
     * already been transferred away.</p>
     */
    public void clearAndRelease() {
        this.linkedList.forEach(SoUtils::release);
        this.linkedList.clear();
        if (this.singleSubQueue != null) {
            this.singleSubQueue.discard();
        }

        if (this.subQueueMap != null && !this.subQueueMap.isEmpty()) {
            for (ProtoQueueSndSubQueue<T> subQueue : new ArrayList<>(this.subQueueMap.values())) {
                subQueue.discard();
            }
            this.subQueueMap.clear();
        }
        this.totalOwned = 0;
    }

    /**
     * Take up to {@code cnt} messages and return them directly as a raw array.
     * <p>This is a performance-oriented bulk-transfer helper used by the protocol stack to avoid
     * intermediate {@link List} allocation. As with {@link #takeMessage(int)}, ownership transfers
     * to the caller after return.</p>
     */
    public Object[] takeMessageToArray(int cnt) {
        if (cnt == 0) {
            return EMPTY_ARRAY;
        }
        if (cnt < 0) {
            cnt = this.linkedList.size();
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
        this.totalOwned -= fixCnt;
        return result;
    }

    @Override
    public void drainToQueue(String key, int cnt) {
        ProtoQueueSndSubQueue<T> queueView = this.ensureSubQueue(key);
        queueView.drainFromMain(cnt);
    }

    @Override
    public void drainToQueue(String key, int cnt, Predicate<T> predicate) {
        if (cnt == 0 || this.linkedList.isEmpty()) {
            return;
        }

        String fixedKey = this.requireKey(key);
        Predicate<T> fixedPredicate = predicate != null ? predicate : item -> true;
        int remaining = cnt < 0 ? Integer.MAX_VALUE : cnt;
        List<T> moved = new ArrayList<>();

        Iterator<T> iterator = this.linkedList.iterator();
        while (iterator.hasNext() && remaining > 0) {
            T item = iterator.next();
            if (!fixedPredicate.test(item)) {
                continue;
            }

            moved.add(item);
            iterator.remove();
            remaining--;
        }

        if (moved.isEmpty()) {
            return;
        }

        ProtoQueueSndSubQueue<T> queueView = this.ensureSubQueue(fixedKey);
        queueView.linkedList.addAll(moved);
    }

    @Override
    public List<String> queueNames() {
        if (this.subQueueMap != null) {
            return new ArrayList<String>(this.subQueueMap.keySet());
        }

        if (this.singleSubQueue == null) {
            return Collections.emptyList();
        }

        List<String> names = new ArrayList<>(1);
        names.add(this.singleSubQueue.getKey());
        return names;
    }

    @Override
    public boolean hasQueue(String key) {
        return key != null && this.findSubQueue(key) != null;
    }

    @Override
    public void discard(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }

        ProtoQueueSndSubQueue<T> subQueue = this.findSubQueue(key);
        if (subQueue != null) {
            subQueue.discard();
        }
    }

    @Override
    public ProtoQueueRcvSubQueue<T> queueView(String key) {
        return this.ensureSubQueue(key);
    }

    ProtoQueueSndSubQueue<T> ensureSubQueue(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("queue view key is blank.");
        }

        ProtoQueueSndSubQueue<T> subQueue = this.findSubQueue(key);
        if (subQueue != null) {
            return subQueue;
        }

        ProtoQueueSndSubQueue<T> created = new ProtoQueueSndSubQueue<>(this, key);
        this.attachSubQueue(key, created);
        return created;
    }

    public ProtoSndQueueView<T> subQueue(String key) {
        String fixedKey = this.requireKey(key);
        ProtoQueueSndSubQueue<T> subQueue = this.findSubQueue(fixedKey);
        if (subQueue != null) {
            return subQueue;
        }
        return new ProtoQueueLazySndSubQueue<T>(this, fixedKey);
    }

    @Override
    public List<String> subKeys() {
        return this.queueNames();
    }

    @Override
    public boolean hasSub(String key) {
        return this.hasQueue(key);
    }

    int totalOwnedSize() {
        int total = this.linkedList.size();
        if (this.singleSubQueue != null) {
            total += this.singleSubQueue.localSize();
        }

        if (this.subQueueMap != null) {
            for (ProtoQueueSndSubQueue<T> subQueue : this.subQueueMap.values()) {
                total += subQueue.localSize();
            }
        }
        return total;
    }

    List<T> mainTake(int cnt) {
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

        List<T> result = new ArrayList<T>(this.linkedList.subList(0, fixCnt));
        this.linkedList.subList(0, fixCnt).clear();
        return result;
    }

    T mainTakeOne() {
        if (this.linkedList.isEmpty()) {
            return null;
        }
        return this.linkedList.remove(0);
    }

    void adjustOwnedSize(int delta) {
        if (delta == 0) {
            return;
        }
        this.totalOwned += delta;
    }

    void mainAddToHead(List<T> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        this.linkedList.addAll(0, items);
    }

    void mainAddToTail(List<T> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        this.linkedList.addAll(items);
    }

    void removeSub(String key, ProtoQueueRcvSubQueue<T> subQueue) {
        if (key == null || subQueue == null) {
            return;
        }

        if (this.singleSubQueue == subQueue && key.equals(subQueue.getKey())) {
            this.singleSubQueue = null;
            return;
        }

        ProtoQueueSndSubQueue<T> current = this.subQueueMap != null ? this.subQueueMap.get(key) : null;
        if (current == subQueue) {
            this.subQueueMap.remove(key);
        }
    }

    String requireKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("queue view key is blank.");
        }
        return key;
    }

    ProtoQueueSndSubQueue<T> attachedSubQueue(String key) {
        String fixedKey = this.requireKey(key);
        return this.findSubQueue(fixedKey);
    }

    private ProtoQueueSndSubQueue<T> findSubQueue(String key) {
        ProtoQueueSndSubQueue<T> single = this.singleSubQueue;
        if (single != null && single.getKey().equals(key)) {
            return single;
        }

        return this.subQueueMap != null ? this.subQueueMap.get(key) : null;
    }

    private void attachSubQueue(String key, ProtoQueueSndSubQueue<T> subQueue) {
        if (this.subQueueMap != null) {
            this.subQueueMap.put(key, subQueue);
        } else if (this.singleSubQueue == null) {
            this.singleSubQueue = subQueue;
        } else {
            // Keep the common single-view case inline; multiple views retain insertion order.
            this.subQueueMap = new LinkedHashMap<>();
            this.subQueueMap.put(this.singleSubQueue.getKey(), this.singleSubQueue);
            this.subQueueMap.put(key, subQueue);
            this.singleSubQueue = null;
        }
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

class ProtoQueueRcvSubQueue<T> implements ProtoRcvQueueView<T> {
    protected final ProtoQueue<T> owner;
    private final   String        key;
    protected final List<T>       linkedList;
    private         boolean       closed;

    ProtoQueueRcvSubQueue(ProtoQueue<T> owner, String key) {
        this.owner = owner;
        this.key = key;
        this.linkedList = new ArrayList<T>();
    }

    ProtoQueue<T> owner() {
        return this.owner;
    }

    boolean isClosed() {
        return this.closed;
    }

    int localSize() {
        return this.closed ? 0 : this.linkedList.size();
    }

    protected boolean isEmpty() {
        return this.linkedList.isEmpty();
    }

    protected void closeIfEmpty() {
        if (!this.closed && this.linkedList.isEmpty()) {
            this.closed = true;
            this.owner.removeSub(this.key, this);
        }
    }

    @Override
    public String getKey() {
        return this.key;
    }

    public int getCapacity() {
        return this.owner.getCapacity();
    }

    @Override
    public int queueSize() {
        return this.closed ? 0 : this.linkedList.size();
    }

    @Override
    public List<T> takeMessage(int cnt) {
        if (this.closed) {
            return Collections.emptyList();
        }
        if (cnt == 0) {
            this.closeIfEmpty();
            return Collections.emptyList();
        }
        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt == 0) {
            this.closeIfEmpty();
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<T>(this.linkedList.subList(0, fixCnt));
        this.linkedList.subList(0, fixCnt).clear();
        this.owner.adjustOwnedSize(-fixCnt);
        this.closeIfEmpty();
        return result;
    }

    @Override
    public Object[] takeMessageToArray(int cnt) {
        if (this.closed) {
            return new Object[0];
        }

        if (cnt == 0) {
            this.closeIfEmpty();
            return new Object[0];
        }

        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt == 0) {
            this.closeIfEmpty();
            return new Object[0];
        }

        Object[] result = new Object[fixCnt];
        for (int i = 0; i < fixCnt; i++) {
            result[i] = this.linkedList.get(i);
        }

        this.linkedList.subList(0, fixCnt).clear();
        this.owner.adjustOwnedSize(-fixCnt);
        this.closeIfEmpty();
        return result;
    }

    @Override
    public T takeMessage() {
        if (this.closed || this.linkedList.isEmpty()) {
            this.closeIfEmpty();
            return null;
        }

        T item = this.linkedList.remove(0);
        this.owner.adjustOwnedSize(-1);
        this.closeIfEmpty();
        return item;
    }

    @Override
    public T peekMessage() {
        if (this.closed || this.linkedList.isEmpty()) {
            this.closeIfEmpty();
            return null;
        }
        return this.linkedList.get(0);
    }

    @Override
    public List<T> peekMessage(int cnt) {
        if (this.closed) {
            return Collections.emptyList();
        }
        if (cnt < 0) {
            cnt = this.linkedList.size();
        }

        int fixCnt = Math.min(cnt, this.linkedList.size());
        return new ArrayList<T>(this.linkedList.subList(0, fixCnt));
    }

    @Override
    public void peekEachMessage(Consumer<? super T> consumer) {
        if (consumer == null || this.closed || this.linkedList.isEmpty()) {
            return;
        }
        for (T item : this.linkedList) {
            consumer.accept(item);
        }
    }

    @Override
    public void peekEachMessage(int startIndex, Consumer<? super T> consumer) {
        if (consumer == null || this.closed || this.linkedList.isEmpty()) {
            return;
        }
        int begin = Math.max(0, startIndex);
        for (int i = begin; i < this.linkedList.size(); i++) {
            consumer.accept(this.linkedList.get(i));
        }
    }

    @Override
    public void skipMessage(int cnt) {
        if (this.closed) {
            return;
        }
        int fixCnt = Math.min(cnt, this.linkedList.size());
        if (fixCnt > 0) {
            for (int i = 0; i < fixCnt; i++) {
                SoUtils.release(this.linkedList.get(i));
            }
            this.linkedList.subList(0, fixCnt).clear();
            this.owner.adjustOwnedSize(-fixCnt);
        }
        this.closeIfEmpty();
    }

    void drainFromMain(int cnt) {
        if (this.closed) {
            return;
        }
        if (cnt == 0) {
            this.closeIfEmpty();
            return;
        }

        if (cnt == 1) {
            T moved = this.owner.mainTakeOne();
            if (moved != null) {
                this.linkedList.add(moved);
            }
            this.closeIfEmpty();
            return;
        }

        List<T> moved = this.owner.mainTake(cnt);
        if (!moved.isEmpty()) {
            this.linkedList.addAll(moved);
        }
        this.closeIfEmpty();
    }

    @Override
    public void discard() {
        if (this.closed) {
            return;
        }
        this.skipMessage(this.linkedList.size());
        this.closeIfEmpty();
    }

    @Override
    public void returnToHead() {
        if (this.closed) {
            return;
        }
        if (this.linkedList.isEmpty()) {
            this.closeIfEmpty();
            return;
        }
        List<T> moved = new ArrayList<T>(this.linkedList);
        this.linkedList.clear();
        this.owner.mainAddToHead(moved);
        this.closeIfEmpty();
    }

    @Override
    public void returnToTail() {
        if (this.closed) {
            return;
        }
        if (this.linkedList.isEmpty()) {
            this.closeIfEmpty();
            return;
        }
        List<T> moved = new ArrayList<T>(this.linkedList);
        this.linkedList.clear();
        this.owner.mainAddToTail(moved);
        this.closeIfEmpty();
    }

    void moveAllToMainTail() {
        this.returnToTail();
    }
}

class ProtoQueueSndSubQueue<T> extends ProtoQueueRcvSubQueue<T> implements ProtoSndQueueView<T> {
    ProtoQueueSndSubQueue(ProtoQueue<T> owner, String key) {
        super(owner, key);
    }

    @Override
    public boolean hasMore() {
        return !this.isClosed() && !this.isEmpty();
    }

    @Override
    public int slotSize() {
        if (this.isClosed()) {
            return 0;
        }
        return Math.max(0, this.owner.getCapacity() - this.owner.totalOwnedSize());
    }

    @Override
    public boolean offerMessage(T[] offerList) {
        if (this.isClosed()) {
            return false;
        }
        if (offerList == null || offerList.length == 0) {
            return false;
        }
        if (this.slotSize() < offerList.length) {
            return false;
        }

        for (int i = 0; i < offerList.length; i++) {
            this.linkedList.add(offerList[i]);
        }
        this.owner.adjustOwnedSize(offerList.length);
        return true;
    }

    @Override
    public boolean offerMessage(T offerMessage) {
        if (this.isClosed()) {
            return false;
        }
        if (this.slotSize() <= 0) {
            return false;
        }
        this.linkedList.add(offerMessage);
        this.owner.adjustOwnedSize(1);
        return true;
    }

    @Override
    public boolean offerMessage(List<T> offerList) {
        if (this.isClosed()) {
            return false;
        }
        if (offerList == null || offerList.isEmpty()) {
            return false;
        }
        if (this.slotSize() < offerList.size()) {
            return false;
        }

        this.linkedList.addAll(offerList);
        this.owner.adjustOwnedSize(offerList.size());
        return true;
    }

    @Override
    public boolean offerMessage(ProtoRcvQueue<T> offerList) {
        if (this.isClosed()) {
            return false;
        }
        if (offerList == null) {
            return false;
        }

        int size = offerList.queueSize();
        if (size <= 0 || this.slotSize() < size) {
            return false;
        }

        List<T> moved = offerList.takeMessage(size);
        if (moved.isEmpty()) {
            return false;
        }
        this.linkedList.addAll(moved);
        this.owner.adjustOwnedSize(moved.size());
        return true;
    }

    @Override
    public void push() {
        this.moveAllToMainTail();
    }
}

class ProtoQueueLazySndSubQueue<T> implements ProtoSndQueueView<T> {
    private final ProtoQueue<T> owner;
    private final String        key;

    ProtoQueueLazySndSubQueue(ProtoQueue<T> owner, String key) {
        this.owner = owner;
        this.key = key;
    }

    private ProtoQueueSndSubQueue<T> attached() {
        return this.owner.attachedSubQueue(this.key);
    }

    private ProtoQueueSndSubQueue<T> ensureAttached() {
        return this.owner.ensureSubQueue(this.key);
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public int slotSize() {
        ProtoQueueSndSubQueue<T> attached = this.attached();
        return attached != null ? attached.slotSize() : this.owner.slotSize();
    }

    @Override
    public boolean offerMessage(T[] offerList) {
        if (offerList == null || offerList.length == 0) {
            return false;
        }

        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            return attached.offerMessage(offerList);
        }

        if (this.owner.slotSize() < offerList.length) {
            return false;
        }
        return this.ensureAttached().offerMessage(offerList);
    }

    @Override
    public boolean offerMessage(T offerMessage) {
        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            return attached.offerMessage(offerMessage);
        }

        if (this.owner.slotSize() <= 0) {
            return false;
        }
        return this.ensureAttached().offerMessage(offerMessage);
    }

    @Override
    public boolean offerMessage(List<T> offerList) {
        if (offerList == null || offerList.isEmpty()) {
            return false;
        }

        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            return attached.offerMessage(offerList);
        }

        if (this.owner.slotSize() < offerList.size()) {
            return false;
        }
        return this.ensureAttached().offerMessage(offerList);
    }

    @Override
    public boolean offerMessage(ProtoRcvQueue<T> offerList) {
        if (offerList == null) {
            return false;
        }

        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            return attached.offerMessage(offerList);
        }

        int size = offerList.queueSize();
        if (size <= 0 || this.owner.slotSize() < size) {
            return false;
        }
        return this.ensureAttached().offerMessage(offerList);
    }

    @Override
    public void discard() {
        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            attached.discard();
        }
    }

    @Override
    public void push() {
        ProtoQueueSndSubQueue<T> attached = this.attached();
        if (attached != null) {
            attached.push();
        }
    }
}