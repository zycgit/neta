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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Receive-side partition duplexer.
 * <p>
 * Unlike {@link ProtoRoutingDuplexer}, which selects a single branch for the whole connection,
 * this duplexer maintains one independent serial handler chain per resolved partition key.
 * Partition-local chains are created from a single {@link ProtoInitializer} and currently only
 * support decoder-style handlers.
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-29
 */
public class ProtoPartitionDuplexer<IN, OUT> implements ProtoDuplexer<IN, IN, OUT, OUT> {
    private final ProtoPartitionSelector<IN>  selector;
    private final Map<String, PartitionState> partitions;
    private       ProtoInitializer            partitionInitializer;
    private       String                      pendingPartitionKey;
    private       int                         partitionRcvSize;
    private       int                         partitionSndSize;
    private       String                      parentPrevStackName;
    private       String                      parentNextStackName;

    public ProtoPartitionDuplexer(ProtoPartitionSelector<IN> selector) {
        this.selector = Objects.requireNonNull(selector, "selector is null.");
        this.partitions = new LinkedHashMap<>();
    }

    public void setInitializer(ProtoInitializer initializer) {
        this.partitionInitializer = Objects.requireNonNull(initializer, "initializer is null.");
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) {
        if (this.partitionInitializer == null) {
            throw new IllegalStateException("ProtoPartitionDuplexer initializer is null.");
        }
        if (!(context instanceof ProtoContextService)) {
            throw new IllegalStateException("ProtoPartitionDuplexer requires ProtoContextService.");
        }

        ProtoContextService parentCtx = (ProtoContextService) context;
        this.partitionRcvSize = rcvSize;
        this.partitionSndSize = sndSize;
        this.parentPrevStackName = parentCtx.getChainRoot().findPreviousStack(name);
        this.parentNextStackName = parentCtx.getChainRoot().findNextStack(name);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (this.partitions.isEmpty()) {
            return true;
        }

        String partitionKey = this.resolvePartitionKey(context, event, isRcv);
        if (partitionKey == null) {
            return true;
        }

        PartitionState state = this.partitions.get(partitionKey);
        if (state != null) {
            return state.onUserEvent(event, isRcv);
        }

        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown,  //
            ProtoRcvQueue<OUT> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {

        if (!isRcv) {
            int sendCount = Math.min(sndUp.queueSize(), sndDown.slotSize());
            if (sendCount > 0) {
                sndDown.offerMessage(sndUp.takeMessage(sendCount));
            }
            return ProtoStatus.Next;
        }

        if (!this.flushPendingPartition(rcvDown)) {
            return ProtoStatus.Next;
        }

        while (rcvUp.hasMore()) {
            IN message = rcvUp.takeMessage();
            if (message == null) {
                continue;
            }

            String partitionKey = this.resolvePartitionKey(context, isRcv, message);
            if (partitionKey == null) {
                continue;
            }

            PartitionState state = this.partitions.get(partitionKey);
            if (state == null) {
                state = this.createPartitionState(partitionKey, context);
                this.partitions.put(partitionKey, state);
            }
            state.process(message);
            if (!state.flushTo(rcvDown)) {
                this.pendingPartitionKey = partitionKey;
                return ProtoStatus.Next;
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        for (PartitionState state : new ArrayList<>(this.partitions.values())) {
            state.close();
        }
        this.partitions.clear();
    }

    //

    private String resolvePartitionKey(ProtoContext context, boolean isRcv, IN message) {
        return this.selector.route(context, isRcv, message);
    }

    private String resolvePartitionKey(ProtoContext context, SoUserEvent event, boolean isRcv) {
        return this.selector.route(context, isRcv, event);
    }

    private PartitionState createPartitionState(String partitionKey, ProtoContext parentContext) throws Throwable {
        if (!(parentContext instanceof ProtoContextService)) {
            throw new IllegalStateException("ProtoPartitionDuplexer requires ProtoContextService.");
        }

        ProtoContextService parentCtx = (ProtoContextService) parentContext;
        ProtoContextService branchCtx = new ProtoContextService(parentCtx, this.partitionRcvSize, this.partitionSndSize, this.parentPrevStackName, this.parentNextStackName);
        ProtoStackChain chainRoot = branchCtx.getChainRoot();
        branchCtx.context(PartitionKeyHolder.class, new PartitionKeyHolder<>(partitionKey));

        this.partitionInitializer.config(branchCtx);
        chainRoot.onInit(branchCtx);
        chainRoot.onActive(branchCtx);

        return new PartitionState(branchCtx, chainRoot);
    }

    private boolean flushPendingPartition(ProtoSndQueue<IN> rcvDown) {
        if (this.pendingPartitionKey == null) {
            return true;
        }

        PartitionState state = this.partitions.get(this.pendingPartitionKey);
        if (state == null) {
            this.pendingPartitionKey = null;
            return true;
        }

        if (!state.flushTo(rcvDown)) {
            return false;
        }

        this.pendingPartitionKey = null;
        return true;
    }

    private final class PartitionState {
        private final ProtoContextService context;
        private final ProtoStackChain     chainRoot;

        private PartitionState(ProtoContextService context, ProtoStackChain chainRoot) {
            this.context = context;
            this.chainRoot = chainRoot;
        }

        private void process(Object message) throws Throwable {
            ChainResult cr = this.chainRoot.onRcv(this.context, null, new Object[] { message }, null);
            if (cr.error != null) {
                throw cr.error;
            }
        }

        private boolean flushTo(ProtoSndQueue<IN> finalOutput) {
            ProtoQueue<Object> branchTailRcvDown = (ProtoQueue<Object>) this.chainRoot.getTailRcvDown();
            int pendingSize = branchTailRcvDown.queueSize();
            if (pendingSize <= 0) {
                return true;
            }
            int flushCount = Math.min(pendingSize, finalOutput.slotSize());
            if (flushCount <= 0) {
                return false;
            }

            for (Object item : branchTailRcvDown.takeMessage(flushCount)) {
                if (!finalOutput.offerMessage((IN) item)) {
                    throw new IllegalStateException("ProtoPartitionDuplexer failed to flush partition output.");
                }
            }

            if (branchTailRcvDown.wasFull()) {
                this.chainRoot.fireRcvRecover();
            }

            return branchTailRcvDown.queueSize() == 0;
        }

        private boolean onUserEvent(SoUserEvent event, boolean isRcv) throws Throwable {
            if (isRcv) {
                return this.chainRoot.onRcvUserEvent(this.context, null, event);
            } else {
                return this.chainRoot.onSndUserEvent(this.context, null, event);
            }
        }

        private void close() {
            this.chainRoot.onClose(this.context);
        }
    }

    public static final class PartitionKeyHolder<KEY> {
        private final KEY partitionKey;

        private PartitionKeyHolder(KEY partitionKey) {
            this.partitionKey = partitionKey;
        }

        public KEY getPartitionKey() {
            return this.partitionKey;
        }
    }
}