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
import java.util.List;

/**
 * Main send-side queue interface.
 * <p>On top of the write semantics defined by {@link ProtoSndData}, it adds capacity management and named send views.</p>
 * <p>A main send queue can maintain multiple named send views, and the data inside those views can eventually be pushed back into the main send queue for continued sending as defined by the implementation.</p>
 * <p>The main send queue and these named send views share the same capacity limit. In other words, after data is written into a view,
 * it still consumes the shared capacity of the entire send-container system rather than gaining extra slots simply because it entered a view.</p>
 * <p>This shared-capacity model is the foundation of send-side backpressure: backpressure decisions are based on the total amount of data currently held by the main queue and all named views together,
 * rather than only by the number of elements in the main queue itself.</p>
 * @param <T> send message type
 * @author Yongchun Zhao (zyc@hasor.net)
 * @version : 2023-10-17
 * @see ProtoSndData
 * @see ProtoSndQueueView
 * @see ProtoQueue
 */
public interface ProtoSndQueue<T> extends ProtoSndData<T> {
    /**
     * Returns the capacity limit of the current main send queue.
     * <p>The returned value is the total capacity shared by the main send queue and all of its named send views.</p>
     */
    int getCapacity();

    /**
     * Returns the send view with the specified name.
     * <p>If the view does not yet exist, whether it is created automatically is implementation-specific.</p>
     * <p>Whether newly created or not, the view shares the same capacity constraint as the current main send queue.</p>
     * @param key send view name
     * @return the corresponding send view
     */
    ProtoSndQueueView<T> newSub(String key);

    /**
     * Returns the names of all send views that currently exist under this main send queue.
     * @return list of all send view names
     */
    List<String> subKeys();

    /**
     * Determines whether a send view with the specified name already exists.
     * @param key send view name
     * @return {@code true} if the view exists
     */
    boolean hasSub(String key);
}