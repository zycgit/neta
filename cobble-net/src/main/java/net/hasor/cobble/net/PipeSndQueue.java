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
package net.hasor.cobble.net;
import java.util.Collections;
import java.util.List;

/**
 * 网络协议层输出端点的数据队列
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 */
public interface PipeSndQueue<T> {

    /** Returns the {@code writerIndex} of this buffer. */
    int slotSize();

    /** can be writer */
    default boolean hasSlot() {
        return slotSize() > 0;
    }

    /**
     * Marks the current {@code writerIndex} in this buffer.
     * You can reposition the current {@code writerIndex} to the marked {@code writerIndex} by calling {@link #sndReset()}.
     * The initial value of the marked {@code writerIndex} is {@code 0}.
     */
    PipeSndQueue<T> sndMark();

    /**
     * Repositions the current {@code writerIndex} to the marked
     * {@code writerIndex} in this buffer.
     *
     * @throws IndexOutOfBoundsException if the current {@code readerIndex} is greater than the marked {@code writerIndex}
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