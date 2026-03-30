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
import java.util.*;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.ProtoPartitionPolicy.ReceivePolicy;

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
    private static final Logger                            logger = Logger.getLogger(ProtoPartitionDuplexer.class);
    private final        ProtoPartitionSelector            selector;
    private final        Map<PartitionKey, PartitionState> partitions;
    private final        ProtoPartitionControl             partitionControl;
    private              ProtoInitializer                  partitionInitializer;
    private              ProtoPartitionPolicy              partitionPolicy;
    private              PartitionKey                      pendingPartitionKey;
    private              int                               partitionRcvSize;
    private              int                               partitionSndSize;
    private              String                            parentPrevStackName;
    private              String                            parentNextStackName;
    private              boolean                           creationLock;
    private final        ArrayList<IN>                     receiveBuffer;

    public ProtoPartitionDuplexer(ProtoPartitionSelector selector) {
        this.selector = Objects.requireNonNull(selector, "selector is null.");
        this.partitions = new LinkedHashMap<>();
        this.receiveBuffer = new ArrayList<>();
        this.partitionPolicy = (a, b, c, d, e) -> ReceivePolicy.Accept;
        this.partitionControl = new ProtoPartitionControl() {
            @Override
            public void lockCreation() {
                creationLock = true;
            }

            @Override
            public void unlockCreation() {
                creationLock = false;
            }

            @Override
            public boolean isLockCreation() {
                return creationLock;
            }

            @Override
            public boolean hasPartition(PartitionKey key) {
                return partitions.containsKey(key);
            }

            @Override
            public boolean closePartition(PartitionKey key) {
                return closePartitionState(key);
            }

            @Override
            public void closeAllPartitions() {
                closeAllPartitionStates();
            }

            @Override
            public int partitionSize() {
                return partitions.size();
            }
        };
    }

    public ProtoPartitionControl getControl() {
        return this.partitionControl;
    }

    public void configDuplexer(ProtoPartitionPolicy policy, ProtoInitializer initializer) {
        if (policy != null) {
            this.partitionPolicy = policy;
        }
        if (initializer != null) {
            this.partitionInitializer = initializer;
        }
    }

    private boolean handlePolicy(ReceivePolicy policy) {
        if (policy == null || policy == ReceivePolicy.Accept) {
            return true;
        } else if (policy == ReceivePolicy.Drop) {
            return false;
        } else if (policy == ReceivePolicy.Reject) {
            throw new IllegalStateException("rejected the trigger by policy.");
        } else {
            throw new IllegalStateException("Unknown ReceivePolicy: " + policy);
        }
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
        PartitionKey partitionKey = this.selector.route(context, PartitionDataKind.Event, event);
        if (partitionKey == null) {
            return true;
        }

        PartitionState state = this.partitions.get(partitionKey);
        if (state == null) {
            if (this.creationLock) {
                logger.warn("[PARTITION] channel=" + context.getChannel().getChannelId() + " skip creating new partition " + partitionKey + " for event while partition creation is lock.");
                return false;
            }

            ReceivePolicy policy = this.partitionPolicy.newPartition(//
                    context, this.partitionControl, partitionKey, PartitionDataKind.Event, event);
            if (!this.handlePolicy(policy)) {
                return false;
            }

            state = this.createPartitionState(partitionKey, context);
            this.partitions.put(partitionKey, state);
        }

        return state.onUserEvent(event, isRcv);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown,      //
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

        if (!rcvUp.hasMore()) {
            return ProtoStatus.Next;
        }

        if (rcvDown.slotSize() <= 0) {
            return ProtoStatus.Next;
        }

        while (rcvUp.hasMore()) {
            IN message = rcvUp.peekMessage();
            if (message == null) {
                rcvUp.skipMessage(1);
                continue;
            }

            PartitionKey key = this.selector.route(context, PartitionDataKind.Message, message);
            if (key == null) {
                rcvUp.skipMessage(1);
                continue;
            }

            PartitionState state = this.partitions.get(key);
            if (state == null) {
                if (this.creationLock) {
                    logger.warn("[PARTITION] channel=" + context.getChannel().getChannelId() + " drop message for new partition " + key + " while partition creation is lock.");
                    rcvUp.skipMessage(1);
                    continue;
                }

                ReceivePolicy policy = this.partitionPolicy.newPartition(//
                        context, this.partitionControl, key, PartitionDataKind.Message, message);
                if (!this.handlePolicy(policy)) {
                    rcvUp.skipMessage(1);
                    continue;
                }

                state = this.createPartitionState(key, context);
                this.partitions.put(key, state);
            }

            int batchCount = this.collectBatchMessages(context, rcvUp, rcvDown.slotSize(), key);
            if (batchCount <= 0) {
                continue;
            }

            state.process(this.receiveBuffer);
            if (!state.flushTo(rcvDown)) {
                this.pendingPartitionKey = key;
                return ProtoStatus.Next;
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.closeAllPartitionStates();
    }

    //

    private PartitionState createPartitionState(PartitionKey key, ProtoContext parentContext) throws Throwable {
        if (!(parentContext instanceof ProtoContextService)) {
            throw new IllegalStateException("ProtoPartitionDuplexer requires ProtoContextService.");
        }

        ProtoContextService branchCtx = new ProtoContextService(//
                (ProtoContextService) parentContext,            //
                this.partitionRcvSize, this.partitionSndSize,   //
                this.parentPrevStackName, this.parentNextStackName);
        ProtoStackChain chainRoot = branchCtx.getChainRoot();
        branchCtx.context(PartitionKey.class, key);

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

    private int collectBatchMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, int downstreamSlotSize, PartitionKey key) {
        this.receiveBuffer.clear();

        int batchLimit = this.partitionRcvSize <= 0 ? 1 : Math.min(rcvUp.queueSize(), this.partitionRcvSize);
        batchLimit = Math.min(batchLimit, Math.max(1, downstreamSlotSize));
        int acceptedCount = 0;
        while (acceptedCount < batchLimit && rcvUp.hasMore()) {
            IN item = rcvUp.peekMessage();
            if (item == null) {
                rcvUp.skipMessage(1);
                continue;
            }

            PartitionKey itemKey = this.selector.route(context, PartitionDataKind.Message, item);
            if (!Objects.equals(key, itemKey)) {
                break;
            }

            this.receiveBuffer.add(rcvUp.takeMessage());
            acceptedCount++;
        }
        return acceptedCount;
    }

    private boolean closePartitionState(PartitionKey key) {
        if (key == null) {
            return false;
        }

        PartitionState removed = this.partitions.remove(key);
        if (removed == null) {
            return false;
        }
        if (key.equals(this.pendingPartitionKey)) {
            this.pendingPartitionKey = null;
        }

        removed.close();
        return true;
    }

    private void closeAllPartitionStates() {
        for (PartitionState state : new ArrayList<>(this.partitions.values())) {
            state.close();
        }
        this.partitions.clear();
        this.pendingPartitionKey = null;
    }

    private final class PartitionState {
        private final ProtoContextService context;
        private final ProtoStackChain     chainRoot;

        private PartitionState(ProtoContextService context, ProtoStackChain chainRoot) {
            this.context = context;
            this.chainRoot = chainRoot;
        }

        private ProtoStatus process(List<?> messages) throws Throwable {
            if (messages == null || messages.isEmpty()) {
                return ProtoStatus.Next;
            }

            ChainResult cr = this.chainRoot.onRcv(this.context, null, messages.toArray(), null);
            if (cr.error != null) {
                throw cr.error;
            } else {
                return cr.status;
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

            Object[] flushArray = branchTailRcvDown.takeMessageToArray(flushCount);
            if (finalOutput.offerMessage((List<IN>) Arrays.asList(flushArray)) != flushArray.length) {
                throw new IllegalStateException("ProtoPartitionDuplexer failed to flush partition output.");
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
}