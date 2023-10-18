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
package net.hasor.cobble.net.channel;
import java.util.Collections;
import java.util.List;

/**
 * The network protocol layer outputs the data queue of the endpoint
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeRcvQueue
 */
public interface PipeSndQueue<T> {

    /** The number of writable slots, default is Integer.MAX. */
    int slotSize();

    /** can be writer */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /**
     * marks slots locke them in this Queue, {@link #sndReset()} will not affect them.
     */
    PipeSndQueue<T> sndMark();

    /**
     * delete the one you just {@link #offerMessage(List)} data.
     */
    PipeSndQueue<T> sndReset();

    /**
     * offer message to queue, return accept count.
     */
    int offerMessage(List<T> cnt);

    /**
     * offer message to queue, return accept status.
     */
    default boolean offerMessage(T message) {
        return this.offerMessage(Collections.singletonList(message)) != 0;
    }
}