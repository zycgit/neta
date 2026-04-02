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
import net.hasor.neta.channel.*;

/**
 * HTTP/2 partition policy that keeps connection-level control events from leaking into
 * per-stream business handlers and enforces stream lifecycle boundaries.
 * <p>
 * This policy is consulted only when the matched partition does not already exist.
 * Returning {@link ReceivePolicy#Drop} for an event means the current event must not create
 * a new partition. It does not mean that the same HTTP/2 event type is globally discarded.
 * Once a stream partition already exists, later RESET and GOAWAY events bypass this policy
 * and are handled by the partition-local lifecycle duplexer.
 */
public class Http2ObjectPartitionPolicy implements ProtoPartitionPolicy {
    private volatile long lastAcceptedStreamId = Long.MAX_VALUE;

    @Override
    public ReceivePolicy newPartition(ProtoContext context, ProtoPartitionControl control, PartitionKey key, PartitionDataKind kind, Object data) {
        switch (kind) {
            case Event:
                return this.partitionForEvent(control, key, data);
            case Message:
                return this.partitionForStream(control, key);
            default:
                return ReceivePolicy.Drop;
        }
    }

    private ReceivePolicy partitionForEvent(ProtoPartitionControl control, PartitionKey key, Object trigger) {
        Object eventData = (trigger instanceof SoEvent) ? ((SoEvent) trigger).getData() : null;
        if (eventData == null) {
            return ReceivePolicy.Drop;
        }

        if (PartitionKey.defaultKey().equals(key) && eventData instanceof AbstractHttp2Event) {
            return ReceivePolicy.Accept;
        } else {
            return ReceivePolicy.Drop;
        }
    }

    void goawayEvent(AbstractHttp2Event eventData) {
        if (eventData instanceof Http2GoawayEvent) {
            Http2GoawayEvent goawayEvent = (Http2GoawayEvent) eventData;
            this.lastAcceptedStreamId = Math.min(this.lastAcceptedStreamId, goawayEvent.lastAcceptedId());
        }
    }

    private ReceivePolicy partitionForStream(ProtoPartitionControl control, PartitionKey key) {
        if (key == null || PartitionKey.defaultKey().equals(key)) {
            return ReceivePolicy.Drop;
        }

        if (this.lastAcceptedStreamId == Long.MAX_VALUE) {
            return ReceivePolicy.Accept;
        }

        long streamId;
        try {
            streamId = Long.parseLong(key.getKey());
        } catch (NumberFormatException e) {
            return ReceivePolicy.Drop;
        }

        if (streamId <= this.lastAcceptedStreamId) {
            return ReceivePolicy.Accept;
        }

        // during the graceful closing process
        control.requestClose(key);
        return ReceivePolicy.Drop;
    }
}