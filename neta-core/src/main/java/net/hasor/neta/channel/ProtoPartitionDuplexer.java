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
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.*;
import net.hasor.neta.channel.routing.ProtoPartitionPolicy.ReceivePolicy;

/**
 * Partition duplexer that splits one connection's message stream into multiple partition sub-pipelines by partition key.
 * <p>Unlike {@link ProtoRoutingDuplexer}, which selects one branch for the entire connection, this
 * duplexer uses the {@link PartitionKey} returned by {@link ProtoPartitionSelector} to maintain an
 * independent partition sub-pipeline for each key. That lets different logical partitions on the
 * same connection keep their own context, buffers, and processing state.</p>
 * <p>Its core responsibility is to centralize the lifecycle details of partition selection,
 * partition creation, partition reuse, and partition closure inside one protocol node. After a
 * message or event enters, it first determines the partition key, then decides whether the
 * corresponding partition sub-pipeline should be created, and finally hands the data to that
 * partition instance.</p>
 * <p>Partition sub-pipelines are created by the initializer logic supplied through
 * {@link ProtoPartitionBuilder}. Whether creation should be allowed or blocked is decided by
 * {@link ProtoPartitionPolicy}, while the number of active partitions and their close behavior are
 * managed through {@link ProtoPartitionControl}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-29
 */
public class ProtoPartitionDuplexer<IN, OUT> implements ProtoDuplexer<IN, IN, OUT, OUT> {
    private static final Logger                            logger          = Logger.getLogger(ProtoPartitionDuplexer.class);
    private static final String                            STAGE_QUEUE_KEY = ProtoPartitionDuplexer.class.getName();
    private final        ProtoPartitionSelector            selector;
    private final        Map<PartitionKey, PartitionState> partitions;
    private final        ProtoPartitionControl             partitionControl;
    private final        String                            recoveryOwnerId;
    private              ProtoInitializer                  partitionInitializer;
    private              ProtoInitializer                  defaultInitializer;
    private              ProtoPartitionPolicy              partitionPolicy;
    private              PartitionKey                      pendingPartitionKey;
    private              boolean                           pendingPartition;
    private              boolean                           closeAllRequested;
    private              int                               partitionRcvSize;
    private              int                               partitionSndSize;
    private              String                            parentPrevStackName;
    private              String                            parentNextStackName;
    private              boolean                           creationLock;

    public ProtoPartitionDuplexer(ProtoPartitionSelector selector) {
        String ownerSuffix = Integer.toHexString(System.identityHashCode(this));
        this.selector = Objects.requireNonNull(selector, "selector is null.");
        this.partitions = new LinkedHashMap<>();
        this.recoveryOwnerId = "partition@" + ownerSuffix;
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
            public boolean contains(PartitionKey key) {
                return partitions.containsKey(key);
            }

            @Override
            public boolean requestClose(PartitionKey key) {
                return requestClosePartitionState(key);
            }

            @Override
            public void requestCloseAll() {
                requestCloseAllPartitionStates();
            }

            @Override
            public boolean isClose(PartitionKey key) {
                PartitionState state = partitions.get(key);
                return state != null && state.isClose();
            }

            @Override
            public boolean closePartition(PartitionKey key) {
                return forceClosePartitionState(key);
            }

            @Override
            public void closeAllPartitions() {
                forceCloseAllPartitionStates();
            }

            @Override
            public Collection<PartitionKey> partitionKeys() {
                return new ArrayList<PartitionKey>(partitions.keySet());
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

    public void configDuplexer(ProtoPartitionPolicy policy, ProtoInitializer partitionInitializer, ProtoInitializer defaultInitializer) {
        if (policy != null) {
            this.partitionPolicy = policy;
        }

        this.partitionInitializer = partitionInitializer;
        this.defaultInitializer = defaultInitializer;
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
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        PartitionKey routeKey = this.selector.route(context, PartitionDataKind.Event, event);
        if (routeKey == null) {
            this.commitRequestedClosures();
            return true;
        }

        PartitionState state;
        if (this.isDefaultPartitionKey(routeKey)) {
            if (this.partitions.containsKey(routeKey)) {
                state = this.partitions.get(routeKey);
            } else if (this.defaultInitializer == null) {
                this.commitRequestedClosures();
                return true;
            } else if (this.creationLock) {
                this.commitRequestedClosures();
                return false;
            } else {
                state = this.ensureDefaultPartitionState(context, PartitionDataKind.Event, event);
            }
        } else {
            state = this.ensurePartitionState(context, routeKey, PartitionDataKind.Event, event);
        }

        if (state != null) {
            boolean result = state.onEvent(event, isRcv);
            this.commitRequestedClosures();
            return result;
        } else {
            this.commitRequestedClosures();
            return this.isDefaultPartitionKey(routeKey);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown,      //
            ProtoRcvQueue<OUT> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {

        if (!isRcv) {
            this.processSendMessages(context, sndUp, sndDown);
            this.commitRequestedClosures();
            return ProtoStatus.Next;
        }

        if (!this.flushPendingOutput(rcvDown)) {
            return ProtoStatus.Next;
        }
        this.commitRequestedClosures();

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

            PartitionKey routeKey = this.selector.route(context, PartitionDataKind.Message, message);
            if (routeKey == null) {
                if (!this.passThroughUnmatchedMessages(context, rcvUp, rcvDown)) {
                    return ProtoStatus.Next;
                }
                continue;
            }

            if (isDefaultPartitionKey(routeKey)) {
                if (this.shouldDropForLockedDefaultPartition(routeKey)) {
                    this.dropUnmatchedMessages(context, rcvUp, routeKey);
                    continue;
                }
                if (!this.processDefaultMessage(context, rcvUp, rcvDown)) {
                    return ProtoStatus.Next;
                }
                continue;
            }

            if (this.shouldDropForLockedPartition(routeKey)) {
                this.dropUnmatchedMessages(context, rcvUp, routeKey);
                continue;
            }

            PartitionState state = this.ensurePartitionState(context, routeKey, PartitionDataKind.Message, message);
            if (state == null) {
                rcvUp.skipMessage(1);
                continue;
            }

            ProtoRcvQueueView<IN> stagedMessages = this.collectBatchMessages(context, rcvUp, rcvDown.slotSize(), routeKey);
            if (stagedMessages == null) {
                continue;
            }

            state.process(stagedMessages);
            if (!state.flushTo(rcvDown)) {
                this.pendingPartitionKey = routeKey;
                this.pendingPartition = true;
                return ProtoStatus.Next;
            }
            this.commitRequestedClosures();
        }

        this.commitRequestedClosures();
        return ProtoStatus.Next;
    }

    private void processSendMessages(ProtoContext context, ProtoRcvQueue<OUT> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
        if (!sndUp.hasMore() || sndDown.slotSize() <= 0) {
            return;
        }

        while (sndUp.hasMore() && sndDown.slotSize() > 0) {
            ProtoRcvQueueView<OUT> stagedMessages = this.collectLeadingMessages(sndUp, Math.min(sndUp.queueSize(), Math.max(1, sndDown.slotSize())), STAGE_QUEUE_KEY);
            if (stagedMessages == null) {
                return;
            }

            if (!sndDown.offerMessage(stagedMessages.takeMessage(-1))) {
                throw new IllegalStateException("ProtoPartitionDuplexer failed to pass through send messages.");
            }
        }
    }

    private <M> ProtoRcvQueueView<M> collectLeadingMessages(ProtoRcvQueue<M> sourceQueue, int batchLimit, String stagingKey) {
        if (batchLimit <= 0) {
            return null;
        }

        int acceptedCount = 0;
        while (acceptedCount < batchLimit && sourceQueue.hasMore()) {
            M item = sourceQueue.peekMessage();
            if (item == null) {
                sourceQueue.skipMessage(1);
                continue;
            }

            sourceQueue.drainToQueue(stagingKey);
            acceptedCount++;
        }
        return acceptedCount <= 0 ? null : sourceQueue.queueView(stagingKey);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.forceCloseAllPartitionStates();
    }

    //

    private PartitionState createPartitionState(PartitionKey routeKey, ProtoContext parentContext, ProtoInitializer initializer) throws Throwable {
        if (!(parentContext instanceof ProtoContextService)) {
            throw new IllegalStateException("ProtoPartitionDuplexer requires ProtoContextService.");
        }
        if (initializer == null) {
            throw new IllegalStateException("ProtoPartitionDuplexer initializer is null.");
        }

        ProtoContextService branchCtx = new ProtoContextService(//
                (ProtoContextService) parentContext,            //
                this.partitionRcvSize, this.partitionSndSize,   //
                this.parentPrevStackName, this.parentNextStackName);
        ProtoStackChain chainRoot = branchCtx.getChainRoot();
        if (routeKey != null) {
            branchCtx.context(PartitionKey.class, routeKey);
            branchCtx.setupRecovery(this.recoveryOwnerId, routeKey.getKey());
        }

        initializer.config(branchCtx);
        chainRoot.onInit(branchCtx);
        chainRoot.onActive(branchCtx);

        return new PartitionState(branchCtx, chainRoot);
    }

    private boolean flushPendingOutput(ProtoSndQueue<IN> rcvDown) {
        if (!this.pendingPartition) {
            return true;
        }

        PartitionState state = this.partitions.get(this.pendingPartitionKey);
        if (state == null) {
            this.pendingPartitionKey = null;
            this.pendingPartition = false;
            return true;
        }

        if (!state.flushTo(rcvDown)) {
            return false;
        }

        this.pendingPartitionKey = null;
        this.pendingPartition = false;
        return true;
    }

    private boolean processDefaultMessage(ProtoContext context, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown) throws Throwable {
        PartitionKey defaultKey = PartitionKey.defaultKey();
        PartitionState state = this.ensureDefaultPartitionState(context, PartitionDataKind.Message, rcvUp.peekMessage());
        if (state == null) {
            return this.passThroughMessages(context, rcvUp, rcvDown, defaultKey);
        }

        ProtoRcvQueueView<IN> stagedMessages = this.collectBatchMessages(context, rcvUp, rcvDown.slotSize(), defaultKey);
        if (stagedMessages == null) {
            return true;
        }

        state.process(stagedMessages);
        if (!state.flushTo(rcvDown)) {
            this.pendingPartition = true;
            this.pendingPartitionKey = defaultKey;
            return false;
        }

        return true;
    }

    private boolean passThroughUnmatchedMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown) {
        return this.passThroughMessages(context, rcvUp, rcvDown, null);
    }

    private boolean passThroughMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<IN> rcvDown, PartitionKey expectedKey) {
        int batchLimit = Math.min(rcvUp.queueSize(), Math.max(1, rcvDown.slotSize()));
        ProtoRcvQueueView<IN> stagedMessages = this.collectMatchedMessages(context, rcvUp, batchLimit, expectedKey, STAGE_QUEUE_KEY);
        if (stagedMessages == null) {
            return true;
        }

        if (!rcvDown.offerMessage(stagedMessages.takeMessage(-1))) {
            throw new IllegalStateException("ProtoPartitionDuplexer failed to pass through unmatched messages.");
        }

        return true;
    }

    private void dropUnmatchedMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, PartitionKey key) throws Throwable {
        ProtoRcvQueueView<IN> stagedMessages = this.collectDroppedMessages(context, rcvUp, key);
        if (stagedMessages == null) {
            return;
        }

        List<IN> unmatchedMessages = stagedMessages.takeMessage(-1);
        PartitionUnmatchedEvent unmatchedEvent = new PartitionUnmatchedEvent(key, unmatchedMessages);
        try {
            context.fireEvent(PartitionUnmatchedEvent.class, unmatchedEvent);
        } finally {
            unmatchedEvent.release();
        }
    }

    private PartitionState ensurePartitionState(ProtoContext context, PartitionKey key, PartitionDataKind kind, Object trigger) throws Throwable {
        PartitionState state = this.partitions.get(key);
        if (state != null) {
            return state;
        }

        ProtoInitializer initializer = this.isDefaultPartitionKey(key) ? this.defaultInitializer : this.partitionInitializer;
        if (initializer == null) {
            return null;
        }

        if (this.creationLock) {
            String keyText = this.isDefaultPartitionKey(key) ? "<default>" : String.valueOf(key);
            logger.warn("[PARTITION] channel=" + context.getChannel().getChannelId() + " skip creating new partition " + keyText + " while partition creation is lock.");
            return null;
        }

        if (!this.isDefaultPartitionKey(key)) {
            ReceivePolicy policy = this.partitionPolicy.newPartition(context, this.partitionControl, key, kind, trigger);
            if (!this.handlePolicy(policy)) {
                return null;
            }
        }

        state = this.createPartitionState(key, context, initializer);
        this.partitions.put(key, state);
        return state;
    }

    private PartitionState ensureDefaultPartitionState(ProtoContext context, PartitionDataKind kind, Object trigger) throws Throwable {
        PartitionKey defaultKey = PartitionKey.defaultKey();
        PartitionState state = this.partitions.get(defaultKey);
        if (state != null) {
            return state;
        }
        if (this.defaultInitializer == null) {
            return null;
        }
        if (this.creationLock) {
            logger.warn("[PARTITION] channel=" + context.getChannel().getChannelId() + " skip creating new partition <default> while partition creation is lock.");
            return null;
        }

        state = this.createDefaultPartitionState(defaultKey, context, this.defaultInitializer);
        if (state == null) {
            logger.warn("[PARTITION] channel=" + context.getChannel().getChannelId() + " default partition initializer an empty " + kind + " will pass through.");
            return null;
        }

        this.partitions.put(defaultKey, state);
        return state;
    }

    private ProtoRcvQueueView<IN> collectBatchMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, int downstreamSlotSize, PartitionKey key) {
        int batchLimit = this.partitionRcvSize <= 0 ? 1 : Math.min(rcvUp.queueSize(), this.partitionRcvSize);
        batchLimit = Math.min(batchLimit, Math.max(1, downstreamSlotSize));
        return this.collectMatchedMessages(context, rcvUp, batchLimit, key, STAGE_QUEUE_KEY);
    }

    private ProtoRcvQueueView<IN> collectDroppedMessages(ProtoContext context, ProtoRcvQueue<IN> rcvUp, PartitionKey key) {
        int batchLimit = this.partitionRcvSize <= 0 ? 1 : Math.min(rcvUp.queueSize(), this.partitionRcvSize);
        return this.collectMatchedMessages(context, rcvUp, batchLimit, key, STAGE_QUEUE_KEY);
    }

    private <M> ProtoRcvQueueView<M> collectMatchedMessages(ProtoContext context, ProtoRcvQueue<M> sourceQueue, int batchLimit, PartitionKey expectedKey, String stagingKey) {
        if (batchLimit <= 0) {
            return null;
        }

        int acceptedCount = 0;
        while (acceptedCount < batchLimit && sourceQueue.hasMore()) {
            M item = sourceQueue.peekMessage();
            if (item == null) {
                sourceQueue.skipMessage(1);
                continue;
            }

            PartitionKey itemKey = this.selector.route(context, PartitionDataKind.Message, item);
            if (!Objects.equals(expectedKey, itemKey)) {
                break;
            }

            sourceQueue.drainToQueue(stagingKey);
            acceptedCount++;
        }

        return acceptedCount <= 0 ? null : sourceQueue.queueView(stagingKey);
    }

    private boolean isDefaultPartitionKey(PartitionKey key) {
        return Objects.equals(PartitionKey.defaultKey(), key);
    }

    private boolean shouldDropForLockedPartition(PartitionKey key) {
        return this.creationLock && key != null && !this.isDefaultPartitionKey(key) && !this.partitions.containsKey(key) && this.partitionInitializer != null;
    }

    private boolean shouldDropForLockedDefaultPartition(PartitionKey key) {
        return this.creationLock && this.isDefaultPartitionKey(key) && !this.partitions.containsKey(key) && this.defaultInitializer != null;
    }

    private boolean requestClosePartitionState(PartitionKey key) {
        PartitionState state = this.partitions.get(key);
        if (state == null) {
            return false;
        }

        state.requestClose();
        return true;
    }

    private void requestCloseAllPartitionStates() {
        this.closeAllRequested = true;
        for (PartitionState state : this.partitions.values()) {
            state.requestClose();
        }
    }

    private boolean forceClosePartitionState(PartitionKey key) {
        PartitionState removed = this.partitions.remove(key);
        if (removed == null) {
            return false;
        }
        if (Objects.equals(key, this.pendingPartitionKey)) {
            this.pendingPartitionKey = null;
            this.pendingPartition = false;
        }

        removed.close();
        return true;
    }

    private void forceCloseAllPartitionStates() {
        for (PartitionState state : new ArrayList<>(this.partitions.values())) {
            state.close();
        }
        this.partitions.clear();
        this.pendingPartitionKey = null;
        this.pendingPartition = false;
        this.closeAllRequested = false;
    }

    private void commitRequestedClosures() {
        if (this.partitions.isEmpty()) {
            this.closeAllRequested = false;
            return;
        }

        List<PartitionKey> readyToClose = new ArrayList<>();
        for (Map.Entry<PartitionKey, PartitionState> entry : this.partitions.entrySet()) {
            if (entry.getValue().canCommitClose()) {
                readyToClose.add(entry.getKey());
            }
        }

        for (PartitionKey key : readyToClose) {
            this.forceClosePartitionState(key);
        }

        if (this.partitions.isEmpty()) {
            this.closeAllRequested = false;
            return;
        }

        if (this.closeAllRequested) {
            boolean allReady = true;
            for (PartitionState state : this.partitions.values()) {
                state.requestClose();
                if (!state.canCommitClose()) {
                    allReady = false;
                }
            }

            if (allReady) {
                this.forceCloseAllPartitionStates();
            }
        }
    }

    private final class PartitionState {
        private final ProtoContextService context;
        private final ProtoStackChain     chainRoot;
        private       boolean             closeRequested;

        private PartitionState(ProtoContextService context, ProtoStackChain chainRoot) {
            this.context = context;
            this.chainRoot = chainRoot;
        }

        private ProtoStatus process(ProtoRcvQueueView<?> messages) throws Throwable {
            if (messages == null || !messages.hasMore()) {
                return ProtoStatus.Next;
            }

            List<?> batchMessages = messages.takeMessage(-1);
            if (batchMessages.isEmpty()) {
                return ProtoStatus.Next;
            }

            ChainResult cr = this.chainRoot.onRcv(this.context, null, batchMessages.toArray(), null);
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
            if (!finalOutput.offerMessage((List<IN>) Arrays.asList(flushArray))) {
                throw new IllegalStateException("ProtoPartitionDuplexer failed to flush partition output.");
            }

            if (branchTailRcvDown.wasFull()) {
                this.chainRoot.fireRcvRecover();
            }

            return branchTailRcvDown.queueSize() == 0;
        }

        private boolean onEvent(SoEvent event, boolean isRcv) throws Throwable {
            if (isRcv) {
                return this.chainRoot.onRcvEvent(this.context, null, event);
            } else {
                return this.chainRoot.onSndEvent(this.context, null, event);
            }
        }

        private void requestClose() {
            this.closeRequested = true;
        }

        private boolean isClose() {
            return this.closeRequested;
        }

        private boolean hasPendingOutput() {
            return this.chainRoot.getTailRcvDown().queueSize() > 0;
        }

        private boolean canCommitClose() {
            return this.closeRequested && !this.hasPendingOutput();
        }

        private void close() {
            this.chainRoot.onClose(this.context);
        }
    }

    private PartitionState createDefaultPartitionState(PartitionKey routeKey, ProtoContext parentContext, ProtoInitializer initializer) throws Throwable {
        if (!(parentContext instanceof ProtoContextService)) {
            throw new IllegalStateException("ProtoPartitionDuplexer requires ProtoContextService.");
        }
        if (initializer == null) {
            return null;
        }

        ProtoContextService branchCtx = new ProtoContextService((ProtoContextService) parentContext, this.partitionRcvSize, this.partitionSndSize, this.parentPrevStackName, this.parentNextStackName);
        ProtoStackChain chainRoot = branchCtx.getChainRoot();
        branchCtx.context(PartitionKey.class, routeKey);
        branchCtx.setupRecovery(this.recoveryOwnerId, routeKey.getKey());

        initializer.config(branchCtx);
        if (chainRoot.isEmpty()) {
            return null;
        }

        chainRoot.onInit(branchCtx);
        chainRoot.onActive(branchCtx);
        return new PartitionState(branchCtx, chainRoot);
    }
}