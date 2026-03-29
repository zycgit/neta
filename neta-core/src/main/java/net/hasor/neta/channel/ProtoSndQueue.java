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
 * <p>This interface now follows immediate-visibility semantics. There is no submit/reset phase:
 * once an {@link #offerMessage} call succeeds, the offered data is already part of the queue.</p>
 * <h3>Slot mechanism</h3>
 * Unlike an unbounded queue, outbound capacity is expressed as <em>slots</em>.  Before
 * offering a message call {@link #hasSlot()} / {@link #slotSize()} to confirm space is
 * available.  If the queue is full (no slots remaining) the pipeline raises
 * {@link ProtoFullException} as a backpressure signal to the sender.
 * <h3>Offer semantics</h3>
 * <p>{@link #offerMessage} is atomic for multi-item writes: the whole batch is accepted or
 * the call returns {@code 0} without changing queue state.</p>
 * @param <T> the type of outbound message
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvQueue
 * @see ProtoQueue
 */
public interface ProtoSndQueue<T> {
    /**
     * Returns queue capacity.
     */
    int getCapacity();

    /** Returns the number of writable slots remaining. */
    int slotSize();

    default boolean wasFull() {
        return this.slotSize() == 0;
    }

    /** Returns {@code true} when at least one message can be written immediately. */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /**
     * Offers an array of messages.
     * <p>The operation is atomic: if there is not enough remaining slot capacity for the
     * whole array then nothing is accepted and {@code 0} is returned.</p>
     * <p>On success, ownership of all offered messages moves to the queue.</p>
     */
    int offerMessage(T[] offerList);

    /**
     * Offers a list of messages.
     * <p>The operation is atomic: if there is not enough remaining slot capacity for the
     * whole list then nothing is accepted and {@code 0} is returned.</p>
     * <p>On success, ownership of all offered messages moves to the queue.</p>
     */
    int offerMessage(List<T> offerList);

    /**
     * Transfers messages from another receive queue.
     * <p>The operation is atomic: if there is not enough remaining slot capacity for all
     * readable items in {@code offerList}, nothing is taken from the source queue and
     * {@code 0} is returned.</p>
     * <p>On success, this method drains the source queue by calling {@link ProtoRcvQueue#takeMessage(int)},
     * so ownership moves from the source queue to this queue in one step.</p>
     */
    int offerMessage(ProtoRcvQueue<T> offerList);

    /**
     * Offers one message and returns whether it was accepted.
     * <p>On success, ownership moves to the queue immediately.</p>
     */
    default boolean offerMessage(T offerMessage) {
        return this.offerMessage(Collections.singletonList(offerMessage)) != 0;
    }
}