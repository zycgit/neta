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
import java.util.List;
import net.hasor.neta.channel.data.*;

class ProtoQueueView implements ProtoRcvQueue<Object>, ProtoSndQueue<Object> {
    private final ProtoQueue<Object> queue;
    private       Runnable           writableCallback;

    public ProtoQueueView(int capacity) {
        this.queue = new ProtoQueue<>(capacity < 0 ? -1 : capacity);
    }

    public void onRecoveredWritable(Runnable callback) {
        this.writableCallback = callback;
    }

    public void clearAndClose() {
        this.queue.clearAndRelease();
    }

    @Override
    public int getCapacity() {
        return this.queue.getCapacity();
    }

    @Override
    public int queueSize() {
        return this.queue.queueSize();
    }

    @Override
    public int slotSize() {
        return this.queue.slotSize();
    }

    @Override
    public boolean offerMessage(Object[] offerList) {
        return this.queue.offerMessage(offerList);
    }

    @Override
    public boolean offerMessage(List<Object> offerList) {
        return this.queue.offerMessage(offerList);
    }

    @Override
    public boolean offerMessage(ProtoRcvQueue<Object> offerList) {
        return this.queue.offerMessage(offerList);
    }

    @Override
    public List<Object> takeMessage(int cnt) {
        boolean wasFull = this.queue.slotSize() == 0;
        List<Object> result = this.queue.takeMessage(cnt);
        this.fireWritableIfRecovered(wasFull);
        return result;
    }

    @Override
    public List<Object> peekMessage(int cnt) {
        return this.queue.peekMessage(cnt);
    }

    @Override
    public void skipMessage(int cnt) {
        boolean wasFull = this.queue.slotSize() == 0;
        this.queue.skipMessage(cnt);
        this.fireWritableIfRecovered(wasFull);
    }

    @Override
    public void drainToQueue(String key, int cnt) {
        this.queue.drainToQueue(key, cnt);
    }

    @Override
    public List<String> queueNames() {
        return this.queue.queueNames();
    }

    @Override
    public boolean hasQueue(String key) {
        return this.queue.hasQueue(key);
    }

    @Override
    public ProtoRcvQueueView<Object> queueView(String key) {
        return this.queue.queueView(key);
    }

    @Override
    public ProtoSndQueueView<Object> newSub(String key) {
        return this.queue.newSub(key);
    }

    @Override
    public List<String> subKeys() {
        return this.queue.subKeys();
    }

    @Override
    public boolean hasSub(String key) {
        return this.queue.hasSub(key);
    }

    private void fireWritableIfRecovered(boolean wasFull) {
        if (!wasFull || this.queue.slotSize() <= 0 || this.writableCallback == null) {
            return;
        }
        this.writableCallback.run();
    }
}