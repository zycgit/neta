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
import java.util.*;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpProtocolStateException;
import net.hasor.neta.codec.http.LastHttpContent;

/**
 * Lifecycle guard for the HTTP/2 partition pipeline.
 * <p>
 * This duplexer is recommended for installation in the default partition. It consumes HTTP/2
 * control events and stream lifecycle events, then closes affected stream partitions from the
 * control plane. For compatibility with older usage patterns, it may still be installed inside a
 * specific stream partition. In that mode, it closes the current partition after both inbound and
 * outbound directions have forwarded terminal {@link LastHttpContent} messages.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-03
 */
public class Http2ObjectStreamManager implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final ProtoPartitionControl       control;
    private final Http2ObjectPartitionPolicy  policy;
    private final Map<Long, StreamCloseState> streamCloseStates;
    private       boolean                     inboundClosed;
    private       boolean                     outboundClosed;
    private       boolean                     partitionClosed;

    /**
     * Creates an HTTP/2 stream lifecycle manager.
     */
    public Http2ObjectStreamManager(ProtoPartitionControl control, Http2ObjectPartitionPolicy policy) {
        this.control = Objects.requireNonNull(control, "control is null.");
        this.policy = Objects.requireNonNull(policy, "policy is null.");
        this.streamCloseStates = new HashMap<>();
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
        Object eventData = event != null ? event.getData() : null;
        if (!(eventData instanceof AbstractHttp2Event)) {
            return true;
        }

        if (!this.isDefaultPartition(context)) {
            return true;
        }

        AbstractHttp2Event http2Event = (AbstractHttp2Event) eventData;
        this.policy.goawayEvent(http2Event);
        if (eventData instanceof Http2StreamCloseEvent) {
            Http2StreamCloseEvent halfClosedEvent = (Http2StreamCloseEvent) eventData;
            this.recordHalfClose(halfClosedEvent.streamId(), halfClosedEvent.inbound());
            return true;
        }
        if (eventData instanceof Http2ResetEvent) {
            Http2ResetEvent resetEvent = (Http2ResetEvent) eventData;
            this.streamCloseStates.remove(resetEvent.streamId());
            this.control.closePartition(PartitionKey.newKey(resetEvent.streamId()));
            return true;
        }
        if (eventData instanceof Http2GoawayEvent) {
            Http2GoawayEvent goawayEvent = (Http2GoawayEvent) eventData;
            this.closeRejectedPartitions(goawayEvent.lastAcceptedId());
            return true;
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) {
        if (this.isDefaultPartition(context)) {
            if (isRcv) {
                return this.forwardPassthrough(rcvUp, rcvDown);
            } else {
                return this.forwardPassthrough(sndUp, sndDown);
            }
        }

        if (isRcv) {
            return this.forward(context, rcvUp, rcvDown, true);
        } else {
            return this.forward(context, sndUp, sndDown, false);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        if (this.isDefaultPartition(context)) {
            this.streamCloseStates.clear();
        }
        this.inboundClosed = false;
        this.outboundClosed = false;
        this.partitionClosed = false;
    }

    private void recordHalfClose(long streamId, boolean inbound) {
        if (streamId <= 0) {
            return;
        }

        StreamCloseState state = this.streamCloseStates.get(streamId);
        if (state == null) {
            state = new StreamCloseState();
            this.streamCloseStates.put(streamId, state);
        }

        if (inbound) {
            state.inboundClosed = true;
        } else {
            state.outboundClosed = true;
        }
        if (state.inboundClosed && state.outboundClosed) {
            this.streamCloseStates.remove(streamId);
            this.control.closePartition(PartitionKey.newKey(streamId));
        }
    }

    private ProtoStatus forward(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst, boolean inbound) {
        ProtoStatus status = this.forwardTracked(src, dst, inbound);
        if (this.inboundClosed && this.outboundClosed) {
            this.closeCurrentPartition(context);
        }

        return status;
    }

    private ProtoStatus forwardPassthrough(ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        while (src.hasMore()) {
            if (!dst.hasSlot()) {
                return ProtoStatus.Next;
            }

            HttpObject message = src.takeMessage();
            if (message == null) {
                continue;
            }

            if (!dst.offerMessage(Collections.singletonList(message))) {
                throw this.forwardFailure(message);
            }
        }
        return ProtoStatus.Next;
    }

    private ProtoStatus forwardTracked(ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst, boolean inbound) {
        while (src.hasMore()) {
            if (!dst.hasSlot()) {
                return ProtoStatus.Next;
            }

            HttpObject message = src.takeMessage();
            if (message == null) {
                continue;
            }
            if (!dst.offerMessage(Collections.singletonList(message))) {
                throw this.forwardFailure(message);
            }

            if (this.isTerminalObject(message)) {
                if (inbound) {
                    this.inboundClosed = true;
                } else {
                    this.outboundClosed = true;
                }
            }
        }
        return ProtoStatus.Next;
    }

    private boolean isTerminalObject(HttpObject message) {
        return message instanceof LastHttpContent;
    }

    private HttpProtocolStateException forwardFailure(HttpObject message) {
        String msg = "HTTP/2 lifecycle duplexer failed to forward stream message.";
        int streamId = message != null ? message.streamId() : 0;
        return streamId > 0 ? new HttpProtocolStateException(streamId, msg) : new HttpProtocolStateException(msg);
    }

    private boolean isDefaultPartition(ProtoContext context) {
        return PartitionKey.defaultKey().equals(PartitionKey.findKey(context));
    }

    private void closeRejectedPartitions(long lastAcceptedId) {
        Collection<PartitionKey> partitionKeys = this.control.partitionKeys();
        for (PartitionKey partitionKey : partitionKeys) {
            if (partitionKey == null || PartitionKey.defaultKey().equals(partitionKey)) {
                continue;
            }
            try {
                if (Long.parseLong(partitionKey.getKey()) > lastAcceptedId) {
                    this.control.requestClose(partitionKey);
                }
            } catch (NumberFormatException e) {
                this.control.requestClose(partitionKey);
            }
        }
    }

    private void closeCurrentPartition(ProtoContext context) {
        if (this.partitionClosed) {
            return;
        }

        PartitionKey currentKey = PartitionKey.findKey(context);
        if (currentKey == null || PartitionKey.defaultKey().equals(currentKey)) {
            return;
        }

        this.control.requestClose(currentKey);
        this.partitionClosed = true;
    }

    private static class StreamCloseState {
        private boolean inboundClosed;
        private boolean outboundClosed;
    }
}