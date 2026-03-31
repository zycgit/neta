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
 * Outbound data queue at one stage boundary of the protocol pipeline.
 * <p>It stores outbound messages already produced by the current stage. Downstream handlers offer
 * messages here, and the upstream boundary or transport layer takes them away and continues sending.</p>
 * <h3>Offer methods</h3>
 * <ul>
 *   <li>{@link #offerMessage(Object[])}, {@link #offerMessage(List)}, and {@link #offerMessage(ProtoRcvQueue)} are used for bulk message offers.</li>
 *   <li>{@link #offerMessage(Object)} is used to offer a single message.</li>
 *   <li>Bulk offers use all-or-nothing semantics. If there are not enough slots, the method returns {@code false} and the queue remains unchanged.</li>
 * </ul>
 * <h3>Slots</h3>
 * <ul>
 *   <li>{@link #slotSize()} reports the current number of remaining writable slots.</li>
 *   <li>{@link #hasSlot()} reports whether more writes are currently possible.</li>
 *   <li>When the queue becomes full, {@link #offerMessage} returns {@code false}.</li>
 * </ul>
 * <h3>Ownership</h3>
 * <ul>
 *   <li>After a successful offer, message ownership transfers to the queue.</li>
 *   <li>When an offer fails, ownership remains with the caller.</li>
 *   <li>When messages are transferred successfully from another {@link ProtoRcvQueue}, ownership of the source queue's messages moves to the current queue in one step.</li>
 * </ul>
 * @param <T> outbound message type
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvQueue
 * @see ProtoQueue
 */
public interface ProtoSndQueue<T> {
    /**
     * Return the queue capacity.
     */
    int getCapacity();

    /** Return the number of remaining writable slots. */
    int slotSize();

    default boolean wasFull() {
        return this.slotSize() == 0;
    }

    /** Return {@code true} when at least one more message can still be written immediately. */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /**
     * Offer an array of messages.
     * <p>This operation is atomic: if remaining slots are insufficient for the entire array, no
     * element is accepted and {@code false} is returned.</p>
     * <p>On success, ownership of all offered messages transfers to the queue.</p>
     */
    boolean offerMessage(T[] offerList);

    /**
     * Offer one message and return whether it was accepted.
     * <p>On success, ownership transfers to the queue immediately.</p>
     */
    default boolean offerMessage(T offerMessage) {
        return this.offerMessage(Collections.singletonList(offerMessage));
    }

    /**
     * Offer a list of messages.
     * <p>This operation is atomic: if remaining slots are insufficient for the entire list, no
     * element is accepted and {@code false} is returned.</p>
     * <p>On success, ownership of all offered messages transfers to the queue.</p>
     */
    boolean offerMessage(List<T> offerList);

    /**
     * Transfer messages from another receive queue.
     * <p>This operation is atomic: if remaining slots are insufficient for all readable elements in
     * {@code offerList}, nothing is taken from the source queue and {@code false} is returned.</p>
     * <p>On success, the method drains the source queue by calling {@link ProtoRcvQueue#takeMessage(int)},
     * so ownership transfers from the source queue to the current queue in one step.</p>
     */
    boolean offerMessage(ProtoRcvQueue<T> offerList);
}