/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.routing.PartitionDataKind;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.channel.routing.ProtoPartitionPolicy;
/**
 * HTTP/2 partition policy that prevents connection-level control events from leaking into
 * per-stream business handlers and strictly preserves stream lifecycle boundaries.
 * <p>
 * This policy is consulted only when the matching partition does not already exist.
 * Returning {@link ReceivePolicy#Drop} for an event means that the current event must not create a
 * new partition; it does not mean that the same class of HTTP/2 event is globally discarded.
 * Once a stream partition already exists, later RESET and GOAWAY events bypass this policy and are
 * handled by the lifecycle duplexer inside that partition.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-03
 */
public class Http2ObjectPartitionPolicy implements ProtoPartitionPolicy {
    private volatile long lastAcceptedStreamId = Long.MAX_VALUE;

    @Override
    /**
     * Decides whether the current data should create a new partition.
     */
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

        // The connection is in graceful shutdown.
        control.requestClose(key);
        return ReceivePolicy.Drop;
    }
}
