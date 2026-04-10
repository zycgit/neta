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
import java.io.Closeable;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Receive-side data container at a protocol stack boundary.
 * <p>It only defines the read semantics for inbound data and does not constrain whether the concrete implementation is a main queue, a sub-view, or a temporary container.</p>
 * <p>Upstream handlers place data into the container, and downstream handlers then take, peek, or discard that data according to the read semantics.</p>
 * <h3>Read operations</h3>
 * <ul>
 *   <li>{@link #takeMessage(int)}: removes data from the container, and after removal the data no longer belongs to the current container.</li>
 *   <li>{@link #peekMessage(int)}: only inspects data without changing the content or order inside the container.</li>
 *   <li>{@link #skipMessage(int)}: directly discards data still held by the container, and the container is responsible for cleaning up the related resources.</li>
 * </ul>
 * <h3>Ownership semantics</h3>
 * <ul>
 *   <li>As long as data remains in the container, ownership belongs to the container itself.</li>
 *   <li>After {@link #takeMessage(int)} succeeds, ownership of the removed data is transferred to the caller.</li>
 *   <li>Calling {@link #peekMessage(int)} does not transfer ownership.</li>
 *   <li>When {@link #skipMessage(int)} is called, the container is responsible for releasing or closing the discarded data.</li>
 * </ul>
 * @param <T> receive message type
 * @author Yongchun Zhao (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvQueue
 * @see ProtoRcvQueueView
 * @see ProtoQueue
 */
public interface ProtoRcvData<T> {
    /**
     * Returns the number of data items currently available for reading.
     */
    int queueSize();

    /**
     * Returns whether at least one data item can be read immediately.
     */
    default boolean hasMore() {
        return queueSize() > 0;
    }

    /**
     * Takes one data item and transfers ownership of that item to the caller.
     * <p>Returns {@code null} when the container is empty.</p>
     */
    default T takeMessage() {
        List<T> msg = this.takeMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Takes up to {@code cnt} data items and transfers ownership of those items to the caller.
     * <p>Before returning, the data has already been removed from the current container.</p>
     * <p>Once taken, the container is no longer responsible for releasing the data or managing its lifecycle.</p>
     * @param cnt maximum number of items to take; a value less than 0 means taking all currently readable data
     */
    List<T> takeMessage(int cnt);

    /**
     * Peeks at the first data item currently available without removing it from the container.
     * <p>This call does not transfer ownership.</p>
     */
    default T peekMessage() {
        List<T> msg = this.peekMessage(1);
        return msg == null || msg.isEmpty() ? null : msg.get(0);
    }

    /**
     * Peeks at up to {@code cnt} data items without changing the content or order inside the container.
     * <p>The return value is a copy, and modifications to the returned list do not affect the container state.</p>
     * <p>This call does not transfer ownership.</p>
     * @param cnt maximum number of items to peek; a value less than 0 means peeking at all currently readable data
     */
    List<T> peekMessage(int cnt);

    /**
     * Skips and discards up to {@code cnt} data items that are still owned by the container.
     * <p>If a discarded object implements {@link ReferenceHolder}, {@link Release}, or {@link Closeable},
     * the container should release or close it during the discard process.</p>
     * @param cnt maximum number of items to discard
     */
    void skipMessage(int cnt);
}