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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-channel outbound queue that temporarily stores {@link SoSndData} waiting to be sent.
 * <h3>Thread model: multiple producers, single consumer</h3>
 * Application threads, any thread, enqueue through {@link #offer(SoSndData)}.
 * One I/O or completion-handler thread dequeues through {@link #peekData()} and {@link #popData()}.
 * The underlying {@link ConcurrentLinkedQueue} already provides the required memory-visibility guarantees,
 * so no explicit locking is needed.
 * <h3>Channel close</h3>
 * If data still remains in the queue when the channel is torn down, the framework calls
 * {@link #purge(Throwable)} to complete each entry's
 * {@link net.hasor.cobble.concurrent.future.Future} exceptionally, typically with
 * {@link SoUnfinishedSndException}, so callers do not lose send results silently.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoSndData
 * @see SoUnfinishedSndException
 */
public class SoSndContext {
    private final Queue<SoSndData> wQueue = new ConcurrentLinkedQueue<>();

    /**
     * Pop one data item from the send queue.
     */
    public SoSndData popData() {
        return this.wQueue.poll();
    }

    /** Purge data that has not been sent yet. */
    public void purge(Throwable e) {
        SoSndData data;
        do {
            data = this.wQueue.poll();
            if (data != null) {
                try {
                    data.failed(e);
                } catch (Exception ignored) {

                }
            }
        } while (data != null);
    }

    /** Peek at the head element of the send queue without removing it. */
    public SoSndData peekData() {
        return this.wQueue.peek();
    }

    /** Add pending data to the send queue. */
    public void offer(SoSndData sndData) {
        this.wQueue.offer(sndData);
    }

    /** Return whether the send queue is empty. */
    public boolean isEmpty() {
        return this.wQueue.isEmpty();
    }
}