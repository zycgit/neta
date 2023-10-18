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
package net.hasor.cobble.net.channel;
import java.util.LinkedList;
import java.util.List;

/**
 * PipeRcvQueue/PipeSndQueue implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeQueue<T> implements PipeRcvQueue<T>, PipeSndQueue<T> {
    protected     int           takeIndex;
    protected     int           offerIndex;
    private final int           capacity;
    private final LinkedList<T> linkedList;

    public PipeQueue(int capacity) {
        this.capacity = capacity;
        this.linkedList = new LinkedList<>();
    }

    @Override
    public int queueSize() {
        return 0;
    }

    @Override
    public int slotSize() {
        return this.capacity - this.linkedList.size();
    }

    @Override
    public PipeRcvQueue<T> rcvMark() {
        this.offerIndex = 0;
        return this;
    }

    @Override
    public PipeRcvQueue<T> rcvReset() {
        synchronized (this.linkedList) {
            for (int i = 0; i < this.offerIndex; i++) {
                this.linkedList.removeLast();
            }
            this.offerIndex = 0;
        }
        return this;
    }

    @Override
    public PipeSndQueue<T> sndMark() {
        return this;
    }

    @Override
    public PipeSndQueue<T> sndReset() {
        return this;
    }

    @Override
    public int offerMessage(List<T> cnt) {
        return 0;
    }

    @Override
    public List<T> takeMessage(int cnt) {
        return null;
    }
}