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
package net.hasor.neta.channel.data;
import java.util.Collections;
import java.util.List;
/**
 * Send-side data container at a protocol stack boundary.
 * <p>It only defines the write semantics for outbound data and does not constrain whether the concrete implementation is a main queue, a sub-view, or a temporary container.</p>
 * <p>Downstream handlers write pending outbound data into the container, and then upper boundaries or the transport layer continue the actual sending from those containers.</p>
 * <h3>Write operations</h3>
 * <ul>
 *   <li>{@link #offerMessage(Object[])} and {@link #offerMessage(List)}: used for batch writes of outbound data.</li>
 *   <li>{@link #offerMessage(Object)}: used for writing a single outbound data item.</li>
 *   <li>{@link #offerMessage(ProtoRcvQueue)}: used for atomically transferring data from another receive container into the current send container.</li>
 * </ul>
 * <h3>Slot semantics</h3>
 * <ul>
 *   <li>{@link #slotSize()} indicates how many writable slots are still available.</li>
 *   <li>{@link #hasSlot()} indicates whether at least one more data item can still be written.</li>
 *   <li>When there is not enough capacity, {@code offerMessage(...)} should return {@code false} and leave the current container unchanged.</li>
 * </ul>
 * <h3>Ownership semantics</h3>
 * <ul>
 *   <li>After a successful write, ownership of the data is transferred to the current send container.</li>
 *   <li>If the write fails, ownership remains with the caller.</li>
 *   <li>When a transfer from another {@link ProtoRcvQueue} succeeds, ownership of the source container's data is transferred in one step to the current send container.</li>
 * </ul>
 * @param <T> send message type
 * @author Yongchun Zhao (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndQueue
 * @see ProtoSndQueueView
 * @see ProtoQueue
 */
public interface ProtoSndData<T> {
    /**
     * Returns the number of writable slots currently remaining.
     */
    int slotSize();

    /**
     * Determines whether the container is already full.
     * @return {@code true} when the remaining slot count is 0
     */
    default boolean wasFull() {
        return this.slotSize() == 0;
    }

    /**
     * Returns whether at least one more data item can be written immediately.
     */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /**
     * Writes all data items from an array as a batch.
     * <p>This operation should be atomic: if the remaining slots are insufficient for the entire array, no element is accepted and {@code false} is returned.</p>
     * <p>After a successful write, ownership of all data items in the array is transferred to the current container.</p>
     * @param offerList data array to be written
     * @return {@code true} if all items are written successfully
     */
    boolean offerMessage(T[] offerList);

    /**
     * Writes one data item and returns whether the write succeeded.
     * <p>After a successful write, ownership of that data item is immediately transferred to the current container.</p>
     * @param offerMessage data item to be written
     * @return {@code true} if the write succeeds
     */
    default boolean offerMessage(T offerMessage) {
        return this.offerMessage(Collections.singletonList(offerMessage));
    }

    /**
     * Writes all data items from a list as a batch.
     * <p>This operation should be atomic: if the remaining slots are insufficient for the entire list, no element is accepted and {@code false} is returned.</p>
     * <p>After a successful write, ownership of all data items in the list is transferred to the current container.</p>
     * @param offerList data list to be written
     * @return {@code true} if all items are written successfully
     */
    boolean offerMessage(List<T> offerList);

    /**
     * Transfers data from another receive container into the current send container.
     * <p>This operation should be atomic: if the current remaining slots are insufficient to accept all readable data in the source container, the source data should not be removed and {@code false} should be returned.</p>
     * <p>After a successful transfer, the source container's data is typically taken in one step by calling {@link ProtoRcvQueue#takeMessage(int)},
     * and ownership is transferred in one step to the current send container.</p>
     * @param offerList source receive container
     * @return {@code true} if the transfer succeeds
     */
    boolean offerMessage(ProtoRcvQueue<T> offerList);
}