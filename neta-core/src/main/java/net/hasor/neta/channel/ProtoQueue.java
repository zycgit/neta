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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ProtoRcvQueue}/{@link ProtoSndQueue} implements
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class ProtoQueue<T> implements ProtoRcvQueue<T>, ProtoSndQueue<T> {
    private final int      capacity;
    private final List<T>  linkedList;
    private final List<T>  offerTemp;
    private final Class<?> elementArrayType;
    protected     int      takeCount;

    public ProtoQueue(int capacity) {
        this.capacity = capacity < 0 ? Integer.MAX_VALUE : capacity;
        this.linkedList = new CopyOnWriteArrayList<>();
        this.offerTemp = new CopyOnWriteArrayList<>();
        this.elementArrayType = this.linkedList.toArray().getClass().getComponentType();
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
    public synchronized ProtoRcvQueue<T> rcvSubmit() {
        this.linkedList.subList(0, this.takeCount).clear();
        this.takeCount = 0;
        return this;
    }

    @Override
    public synchronized ProtoRcvQueue<T> rcvReset() {
        this.takeCount = 0;
        return this;
    }

    @Override
    public synchronized ProtoSndQueue<T> sndSubmit() {
        this.linkedList.addAll(this.offerTemp);
        this.offerTemp.clear();
        return this;
    }

    @Override
    public synchronized ProtoSndQueue<T> sndReset() {
        this.offerTemp.clear();
        return this;
    }

    @Override
    public synchronized int offerMessage(T[] offerList) {
        int size = Math.min(this.slotSize(), offerList.length);
        for (int i = 0; i < size; i++) {
            this.offerTemp.add(offerList[i]);
        }
        return size;
    }

    @Override
    public synchronized int offerMessage(List<T> offerList) {
        int size = Math.min(this.slotSize(), offerList.size());
        for (int i = 0; i < size; i++) {
            this.offerTemp.add(offerList.get(i));
        }
        return size;
    }

    @Override
    public synchronized int offerMessage(ProtoRcvQueue<T> offerList) {
        int size = Math.min(offerList.queueSize(), this.slotSize());
        return this.offerMessage(offerList.takeMessage(size));
    }

    @Override
    public synchronized List<T> takeMessage(int cnt) {
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

    @Override
    public synchronized List<T> peekMessage(int cnt) {
        if (cnt < 0) {
            cnt = this.queueSize();
        }

        int fixCnt = Math.min(cnt, this.queueSize());
        return this.linkedList.subList(this.takeCount, this.takeCount + fixCnt);
    }

    @Override
    public synchronized void skipMessage(int cnt) {
        int fixCnt = Math.min(cnt, this.queueSize());
        this.takeCount += fixCnt;
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