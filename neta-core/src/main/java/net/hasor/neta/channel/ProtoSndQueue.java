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
 * Outbound (send-side) data queue for a protocol layer endpoint.
 * <p>Supports offer operations with transactional semantics
 * ({@link #sndSubmit()}/{@link #sndReset()}).</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoRcvQueue
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