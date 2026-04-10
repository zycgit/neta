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

/**
 * Named view container on the send side.
 * <p>It represents a local send view derived from a main send queue. It has its own write area, but capacity is still governed by the owning main queue.</p>
 * <p>Data in the view can be discarded directly, or pushed back to the main send queue as a whole at an appropriate time.</p>
 * <p>Note that the view does not own independent capacity. Data in the view and data in the owning main queue consume the same shared capacity,
 * which is also the foundation that allows send-side backpressure to cover the main queue and all named views together.</p>
 * @param <T> send message type
 * @author Yongchun Zhao (zyc@hasor.net)
 * @version : 2026-04-10
 */
public interface ProtoSndQueueView<T> extends ProtoSndData<T> {
    /**
     * Returns the name of the current send view.
     */
    String getKey();

    /**
     * Discards all data in the current send view and removes the view from its owning main send queue.
     * <p>Once the data in the view is discarded, the corresponding shared capacity is released as well.</p>
     */
    void discard();

    /**
     * Pushes all data in the current send view back into the owning main send queue.
     * <p>After the push, the current view should no longer continue to hold that data.</p>
     * <p>This operation only changes where the data is located. It does not change how that data consumes shared capacity.</p>
     */
    void push();
}