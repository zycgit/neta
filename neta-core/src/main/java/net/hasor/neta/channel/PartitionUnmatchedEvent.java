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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ReferenceHolder;

/**
 * Event payload describing a batch of inbound messages that could not enter a newly created partition.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-02
 */
public class PartitionUnmatchedEvent implements SoEventData, Release {
    private static final Logger logger = Logger.getLogger(PartitionUnmatchedEvent.class);
    private final        PartitionKey partitionKey;
    private final        List<Object> messages;
    private final        List<Object> readOnlyView;

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
        for (Object item : this.messages) {
            releaseOwned(item);
        }
        this.messages.clear();
    }

    private static void releaseOwned(Object item) {
        try {
            if (item instanceof ReferenceHolder) {
                ((ReferenceHolder) item).release();
            } else if (item instanceof Release) {
                ((Release) item).release();
            } else if (item instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) item);
            }
        } catch (Throwable e) {
            logger.error("PartitionUnmatchedEvent release failed: " + e.getMessage(), e);
        }
    }
}