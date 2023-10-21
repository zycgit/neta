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
package net.hasor.cobble.net.handler;
import java.util.List;

/**
 * Network protocol layer input endpoint data queue
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeSndQueue
 */
public interface PipeRcvQueue<T> {
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
    PipeRcvQueue<T> rcvMark();

    /**
     * Reset the queue, and the data that has been fetched will be returned.
     *
     * <p>The method does not guarantee the data itself status.</p>
     */
    PipeRcvQueue<T> rcvReset();

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
        List<T> msg = this.takeMessage(1);
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