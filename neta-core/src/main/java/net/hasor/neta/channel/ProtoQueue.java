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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link ProtoRcvQueue}/{@link ProtoSndQueue} implements
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class ProtoQueue<T> implements ProtoRcvQueue<T>, ProtoSndQueue<T> {
    private static final Object[] EMPTY_ARRAY = new Object[0];
    private final        int      capacity;
    private final        List<T>  linkedList;
    private final        List<T>  offerTemp;
    protected            int      takeCount;

    public ProtoQueue(int capacity) {
        this.capacity = capacity < 0 ? Integer.MAX_VALUE : capacity;
        this.linkedList = new ArrayList<>();
        this.offerTemp = new ArrayList<>();
    }

    @Override
    public int getCapacity() {
        return this.capacity;
    }

    @Override
    public int queueSize() {
        return this.linkedList.size() - this.takeCount;
    }

    @Override
    public int slotSize() {
        return this.capacity - this.linkedList.size() - this.offerTemp.size();
    }

    @Override
    public boolean hasCommit() {
        return this.takeCount > 0 || !this.offerTemp.isEmpty();
    }

    @Override
    public ProtoRcvQueue<T> rcvSubmit() {
        if (this.takeCount == 1) {
            // Fast path for the common single-element case: avoid SubList allocation
            this.linkedList.remove(0);
        } else if (this.takeCount > 1) {
            this.linkedList.subList(0, this.takeCount).clear();
        }
        this.takeCount = 0;
        return this;
    }

    @Override
    public ProtoRcvQueue<T> rcvReset() {
        this.takeCount = 0;
        return this;
    }

    @Override
    public ProtoSndQueue<T> sndSubmit() {
        int size = this.offerTemp.size();
        if (size == 1) {
            // Fast path: avoid addAll overhead for single element
            this.linkedList.add(this.offerTemp.get(0));
        } else if (size > 1) {
            this.linkedList.addAll(this.offerTemp);
        }
        this.offerTemp.clear();
        return this;
    }

    @Override
    public ProtoSndQueue<T> sndReset() {
        this.offerTemp.clear();
        return this;
    }

    @Override
    public int offerMessage(T[] offerList) {
        int size = Math.min(this.slotSize(), offerList.length);
        this.offerTemp.addAll(Arrays.asList(offerList).subList(0, size));
        return size;
    }

    @Override
    public int offerMessage(List<T> offerList) {
        int size = Math.min(this.slotSize(), offerList.size());
        for (int i = 0; i < size; i++) {
            this.offerTemp.add(offerList.get(i));
        }
        return size;
    }

    /** Single-element fast path: avoid Collections.singletonList allocation */
    @Override
    public boolean offerMessage(T offerMessage) {
        if (this.slotSize() <= 0) {
            return false;
        }
        this.offerTemp.add(offerMessage);
        return true;
    }

    @Override
    public int offerMessage(ProtoRcvQueue<T> offerList) {
        int size = Math.min(offerList.queueSize(), this.slotSize());
        return this.offerMessage(offerList.takeMessage(size));
    }

    /** Single-element fast path: avoid ArrayList allocation */
    @Override
    public T takeMessage() {
        if (this.queueSize() <= 0) {
            return null;
        }
        T result = this.linkedList.get(this.takeCount);
        this.takeCount++;
        return result;
    }

    @Override
    public List<T> takeMessage(int cnt) {
        if (cnt == 0) {
            return Collections.emptyList();
        }

        if (cnt < 0) {
            cnt = this.queueSize();
        }

        int fixCnt = Math.min(cnt, this.queueSize());
        int to = this.takeCount + fixCnt;

        List<T> result = new ArrayList<>(fixCnt);
        for (int i = this.takeCount; i < to; i++) {
            result.add(this.linkedList.get(i));
        }
        this.takeCount += fixCnt;
        return result;
    }

    /** Single-element fast path: avoid ArrayList allocation */
    @Override
    public T peekMessage() {
        if (this.queueSize() <= 0) {
            return null;
        }
        return this.linkedList.get(this.takeCount);
    }

    @Override
    public List<T> peekMessage(int cnt) {
        if (cnt < 0) {
            cnt = this.queueSize();
        }

        int fixCnt = Math.min(cnt, this.queueSize());
        return new ArrayList<>(this.linkedList.subList(this.takeCount, this.takeCount + fixCnt));
    }

    @Override
    public void skipMessage(int cnt) {
        int fixCnt = Math.min(cnt, this.queueSize());
        this.takeCount += fixCnt;
    }

    /** Direct Object[] return to avoid intermediate ArrayList + toArray() */
    public Object[] takeMessageToArray(int cnt) {
        if (cnt <= 0) {
            return EMPTY_ARRAY;
        }
        int fixCnt = Math.min(cnt, this.queueSize());
        if (fixCnt == 0) {
            return EMPTY_ARRAY;
        }
        Object[] result = new Object[fixCnt];
        for (int i = 0; i < fixCnt; i++) {
            result[i] = this.linkedList.get(this.takeCount + i);
        }
        this.takeCount += fixCnt;
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