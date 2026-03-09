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
 * Per-channel outbound queue that stages {@link SoSndData} items awaiting transmission.
 * <h3>Threading model: multi-producer, single-consumer</h3>
 * Application threads (any thread) enqueue items via {@link #offer(SoSndData)}. The single
 * I/O / completion-handler thread drains items via {@link #peekData()} and
 * {@link #popData()}.  The backing {@link ConcurrentLinkedQueue} provides all necessary
 * memory-visibility guarantees without an explicit lock.
 * <h3>Channel close</h3>
 * If the channel is torn down while items remain in the queue, {@link #purge(Throwable)}
 * is called to complete each item’s {@link net.hasor.cobble.concurrent.future.Future}
 * exceptionally with a {@link SoUnfinishedSndException}, so callers are notified rather
 * than silently abandoned.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoSndData
 * @see SoUnfinishedSndException
 */
public class SoSndContext {
    private final Queue<SoSndData> wQueue = new ConcurrentLinkedQueue<>();

    /**
     * poll data form wQueue
     */
    public SoSndData popData() {
        return this.wQueue.poll();
    }

    /** purge hasn't sent data */
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

    /** peek data form wQueue */
    public SoSndData peekData() {
        return this.wQueue.peek();
    }

    /** offer data to send */
    public void offer(SoSndData sndData) {
        this.wQueue.offer(sndData);
    }

    /** test wQueue is empty */
    public boolean isEmpty() {
        return this.wQueue.isEmpty();
    }
}