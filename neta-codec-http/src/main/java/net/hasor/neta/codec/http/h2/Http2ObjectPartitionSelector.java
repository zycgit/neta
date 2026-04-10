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
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.routing.PartitionDataKind;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoPartitionSelector;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Partition selector that maps HTTP/2 traffic to child pipelines according to {@code streamId}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-03
 */
public class Http2ObjectPartitionSelector implements ProtoPartitionSelector {
    @Override
    public PartitionKey route(ProtoContext context, PartitionDataKind kind, Object data) {
        switch (kind) {
            case Message:
                return this.routeMessage(data);
            case Event:
                return this.routeEvent(data);
            default:
                return null;
        }
    }

    private PartitionKey routeMessage(Object data) {
        return this.partitionForData(data);
    }

    private PartitionKey routeEvent(Object data) {
        if (!(data instanceof SoEvent)) {
            return null;
        }

        Object eventData = ((SoEvent) data).getData();
        if (eventData instanceof AbstractHttp2Event) {
            return PartitionKey.defaultKey(); // HTTP/2 event only.
        }

        return null;
    }

    private PartitionKey partitionForData(Object data) {
        if (data instanceof HttpObject) {
            return this.streamPartition(((HttpObject) data).streamId());
        }

        return null;
    }

    private PartitionKey streamPartition(long streamId) {
        return streamId > 0 ? PartitionKey.newKey(streamId) : null;
    }
}