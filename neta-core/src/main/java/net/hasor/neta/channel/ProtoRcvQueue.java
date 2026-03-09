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

/**
 * Inbound (receive-side) data queue for one endpoint of a protocol pipeline stage.
 * <p>Each pipeline stage boundary has exactly one {@code ProtoRcvQueue}: the upstream
 * producer (e.g. a decoder) offers decoded messages into it; the downstream consumer
 * reads them out.
 * <h3>Transaction semantics (two-phase read)</h3>
 * <ol>
 *   <li>Call {@link #takeMessage(int)} to tentatively dequeue items (they are removed from
 *       the visible queue size but not yet finalised).</li>
 *   <li>Call {@link #rcvSubmit()} to confirm the consume; the capacity slot is freed so a
 *       new offer becomes possible.</li>
 *   <li>Or call {@link #rcvReset()} to roll back: the taken items are returned to the front
 *       of the queue as if they were never consumed.</li>
 * </ol>
 * <p>Use {@link #peekMessage(int)} for a non-destructive look-ahead that does not need
 * {@link #rcvSubmit()} to finalise.
 * @param <T> the type of received message
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndQueue
 * @see ProtoQueue
 */
public interface ProtoRcvQueue<T> {
    /**
     * Returns the number of capacity.
     */
    int getCapacity();

    /**
     * Returns the number of readable message.
     */
    int queueSize();

    /** can be read */
    default boolean hasMore() {
        return queueSize() > 0;
    }

    /**
     * Mark the status of the Queue, and new data can be welcomed.
     */
    ProtoRcvQueue<T> rcvSubmit();

    /**
     * Reset the queue, and the data that has been fetched will be returned.
     * <p>The method does not guarantee the data itself status.</p>
     */
    ProtoRcvQueue<T> rcvReset();

    /**
     * take message form queue
     */
    default T takeMessage() {
        List<T> msg = this.takeMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * take message form queue
     * @param cnt The expected number of tack
     */
    List<T> takeMessage(int cnt);

    /**
     * take message form queue
     */
    default T peekMessage() {
        List<T> msg = this.peekMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * peek message form queue
     * @param cnt The expected number of tack
     */
    List<T> peekMessage(int cnt);

    /**
     * skip message form queue
     * @param cnt The expected number of tack
     */
    void skipMessage(int cnt);
}