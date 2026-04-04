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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.function.Release;

/**
 * Event payload describing a batch of inbound messages that could not enter a newly created partition.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-02
 */
public class PartitionUnmatchedEvent implements SoEventData, Release {
    private final PartitionKey partitionKey;
    private final List<Object> messages;
    private final List<Object> readOnlyView;

    public PartitionUnmatchedEvent(PartitionKey partitionKey, List<?> messages) {
        this.partitionKey = partitionKey;
        this.messages = new ArrayList<Object>(messages);
        this.readOnlyView = Collections.unmodifiableList(this.messages);
    }

    public PartitionKey getPartitionKey() {
        return this.partitionKey;
    }

    public List<Object> getMessages() {
        return this.readOnlyView;
    }

    @Override
    public void release() {
        this.messages.forEach(SoUtils::release);
        this.messages.clear();
    }
}