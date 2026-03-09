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
import java.util.Collections;
import java.util.List;

/**
 * Outbound (send-side) data queue for one endpoint of a protocol pipeline stage.
 * <p>Each pipeline stage boundary has exactly one {@code ProtoSndQueue}: the downstream
 * handler (encoder / transformer) offers encoded messages into it; the upstream consumer
 * (or the transport layer) reads them for transmission.
 * <h3>Slot mechanism</h3>
 * Unlike an unbounded queue, outbound capacity is expressed as <em>slots</em>.  Before
 * offering a message call {@link #hasSlot()} / {@link #slotSize()} to confirm space is
 * available.  If the queue is full (no slots remaining) the pipeline raises
 * {@link ProtoFullException} as a backpressure signal to the sender.
 * <h3>Transaction semantics (two-phase write)</h3>
 * <ol>
 *   <li>Call {@link #offerMessage} to tentatively enqueue items.</li>
 *   <li>Call {@link #sndSubmit()} to lock those items so they are visible to the consumer
 *       and cannot be rolled back.</li>
 *   <li>Or call {@link #sndReset()} to discard items that were offered but not yet
 *       committed.</li>
 * </ol>
 * @param <T> the type of outbound message
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvQueue
 * @see ProtoQueue
 */
public interface ProtoSndQueue<T> {
    /**
     * Returns the number of capacity.
     */
    int getCapacity();

    /** Number of writable slots remaining. Default is {@code Integer.MAX_VALUE}. */
    int slotSize();

    /** can be writer */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /** Returns {@code true} if there are uncommitted changes (takes or offers). */
    boolean hasCommit();

    /**
     * marks slots locke them in this Queue, {@link #sndReset()} will not affect them.
     */
    ProtoSndQueue<T> sndSubmit();

    /**
     * delete the one you just {@link #offerMessage(List)} data.
     */
    ProtoSndQueue<T> sndReset();

    /**
     * offer message to queue, return accept count.
     */
    int offerMessage(T[] offerList);

    /**
     * offer message to queue, return accept count.
     */
    int offerMessage(List<T> offerList);

    /**
     * offer message to queue, return accept count.
     */
    int offerMessage(ProtoRcvQueue<T> offerList);

    /**
     * offer message to queue, return accept status.
     */
    default boolean offerMessage(T offerMessage) {
        return this.offerMessage(Collections.singletonList(offerMessage)) != 0;
    }
}