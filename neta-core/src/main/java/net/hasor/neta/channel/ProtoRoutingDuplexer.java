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

/**
 * Routing duplexer.
 * <p>It splits the protocol stack at the current position into multiple named branches and, at
 * runtime, sends the current data or event into one of those branches for execution.</p>
 * <p>Routing decisions may happen at three points:</p>
 * <ul>
 *   <li>{@link #onActive(ProtoContext)}: perform one routing probe with an empty receive queue.</li>
 *   <li>The RCV phase of {@link #onMessage(ProtoContext, boolean, ProtoRcvQueue, ProtoSndQueue, ProtoRcvQueue, ProtoSndQueue)}: compute the branch from real inbound data.</li>
 *   <li>{@link #onEvent(ProtoContext, SoEvent, boolean)}: compute the branch from a network event.</li>
 * </ul>
 * <p>In static mode, the first successfully selected branch keeps being reused. In realtime mode,
 * the data selector is run again for every inbound message.</p>
 * <p>All branches execute {@code onInit} and {@code onClose}. When an already activated branch is
 * selected again, the framework sends a {@link ProtoRouteEvent}.</p>
 * <pre>
 *  Main Pipeline:
 *    [Handler A] → [Router] → [Handler Z]
 *                    │
 *            ┌───────┴───────┐
 *            ↓               ↓
 *     Branch "tls"    Branch "http"
 *      [TlsHandler]   [HttpCodec]
 *      [HttpCodec]
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public class ProtoRoutingDuplexer<IN, OUT> implements ProtoDuplexer<IN, Object, Object, OUT> {
    private static final Logger                            logger = Logger.getLogger(ProtoRoutingDuplexer.class);
    private final        ProtoRoutingDataSelector<IN, OUT> routing4Data;
    private final        ProtoRoutingEventSelector         routing4Event;
    private final        Map<String, BranchEntry>          branches;
    private final        List<String>                      branchOrder;
    private final        Set<String>                       activatedBranches;
    private final        ProtoRoutingControl               routingControl;
    private final        ProtoRoutingMode                  routingMode;
    private final        String                            recoveryOwnerId;
    private              String                            selectedRoute;
    private              String                            pendingRoute;
    private              Object                            pendingRouteSeed;
    private              Object                            deliveredSeed;

    /** Create a static data-routing duplexer. */
    public ProtoRoutingDuplexer(ProtoRoutingDataSelector<IN, OUT> routing) {
        this(ProtoRoutingMode.STATIC, routing);
    }

    /**
     * Create a data-routing duplexer.
     * @param routingMode routing mode
     * @param routing data-routing selector
     */
    public ProtoRoutingDuplexer(ProtoRoutingMode routingMode, ProtoRoutingDataSelector<IN, OUT> routing) {
        this.routingMode = Objects.requireNonNull(routingMode, "routingMode is null.");
        this.routing4Data = Objects.requireNonNull(routing, "routing is null.");
        this.routing4Event = null;
        this.branches = new LinkedHashMap<>();
        this.branchOrder = new ArrayList<>();
        this.activatedBranches = new LinkedHashSet<>();
        this.routingControl = this.initRoutingControl(routingMode);
        this.recoveryOwnerId = "route@" + Integer.toHexString(System.identityHashCode(this));
    }

    /** Create a routing duplexer that performs static branch selection based on network events. */
    public ProtoRoutingDuplexer(ProtoRoutingEventSelector routing) {
        this.routingMode = ProtoRoutingMode.STATIC;
        this.routing4Data = null;
        this.routing4Event = Objects.requireNonNull(routing, "routing is null.");
        this.branches = new LinkedHashMap<>();
        this.branchOrder = new ArrayList<>();
        this.activatedBranches = new LinkedHashSet<>();
        this.routingControl = this.initRoutingControl(this.routingMode);
        this.recoveryOwnerId = "route@" + Integer.toHexString(System.identityHashCode(this));
    }

    private ProtoRoutingControl initRoutingControl(ProtoRoutingMode routingMode) {
        return new ProtoRoutingControl() {
            @Override
            public ProtoRoutingMode mode() {
                return routingMode;
            }

            @Override
            public String current() {
                return selectedRoute;
            }

            @Override
            public boolean hasSeed() {
                return deliveredSeed != null;
            }

            @Override
            public Object peekSeed() {
                return deliveredSeed;
            }

            @Override
            public Object takeSeed() {
                Object currentSeed = deliveredSeed;
                deliveredSeed = null;
                return currentSeed;
            }

            @Override
            public void removeSeed() {
                clearSeedState();
            }

            @Override
            public void switchRoute(String target) {
                schedulePendingUpgrade(target);
            }

            @Override
            public void switchRoute(String target, Object seed) {
                schedulePendingUpgrade(target, seed);
            }
        };
    }

    /** Record a pending branch-switch request to be executed later. */
    private void schedulePendingUpgrade(String newBranchName) {
        this.schedulePendingUpgrade(newBranchName, null);
    }

    /** Record a pending branch-switch request with a one-shot seed object. */
    private void schedulePendingUpgrade(String newBranchName, Object seed) {
        if (!this.branches.containsKey(newBranchName)) {
            throw new IllegalArgumentException("Unknown branch '" + newBranchName + "' for upgrade, available: " + this.branches.keySet());
        }

        this.pendingRoute = newBranchName;

        if (seed != null) {
            this.pendingRouteSeed = this.releaseSeed(this.pendingRouteSeed);
            this.deliveredSeed = this.releaseSeed(this.deliveredSeed);
            this.pendingRouteSeed = seed;
        }
    }

    private void clearSeedState() {
        this.pendingRouteSeed = this.releaseSeed(this.pendingRouteSeed);
        this.deliveredSeed = this.releaseSeed(this.deliveredSeed);
    }

    private Object releaseSeed(Object seed) {
        if (seed == null) {
            return null;
        }
        SoUtils.release(seed);
        return null;
    }

    public ProtoRoutingControl getControl() {
        return this.routingControl;
    }

    /**
     * Initialize all registered branches.
     * <p>Each branch receives its own child context and child pipeline, and runs its own
     * {@code onInit} here.</p>
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        if (this.branchOrder.isEmpty()) {
            throw new IllegalStateException("ProtoRoutingDuplexer has no branches registered. At least one branch is required.");
        }

        ProtoContextService parentCtxService = (ProtoContextService) context;
        String routerName = context.getStackName();
        String prevStackName = parentCtxService.getChainRoot().findPreviousStack(routerName);
        String nextStackName = parentCtxService.getChainRoot().findNextStack(routerName);

        for (String branchName : this.branchOrder) {
            BranchEntry entry = this.branches.get(branchName);
            ProtoContextService branchCtx = new ProtoContextService(parentCtxService, rcvSize, sndSize, prevStackName, nextStackName);
            branchCtx.setupRecovery(this.recoveryOwnerId, branchName);

            ProtoStackChain chainRoot = branchCtx.getChainRoot();
            // Run the user-supplied initializer to populate the branch handler chain
            entry.branchCtx = branchCtx;
            entry.chainRoot = chainRoot;
            entry.initializer.config(branchCtx);
            chainRoot.onInit(branchCtx);
        }
    }

    /**
     * Activate branch pipelines.
     * <p>Realtime mode activates all branches here. Static mode first attempts one empty-input
     * routing probe and activates the target branch when the probe succeeds.</p>
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        if (this.routingMode == ProtoRoutingMode.REALTIME) {
            for (String branchName : this.branchOrder) {
                this.activateBranchLifecycle(context, branchName, null, false);
            }
            return;
        }

        if (this.selectedRoute == null && this.routing4Data != null) {
            // No pre-selection: ask the routing selector (context-based, e.g. ALPN)
            ProtoSndQueue<?> headSndDown = ((ProtoContextService) context).getChainRoot().getHeadSndDown();
            this.selectedRoute = this.routing4Data.route(context, ProtoQueue.emptyRcv(), (ProtoSndQueue<OUT>) headSndDown);
        }
        if (this.selectedRoute != null) {
            this.activateBranchLifecycle(context, this.selectedRoute, null, false);
        }
        // If selectedRoute is still null, branch activation is deferred to onMessage(isRcv=true)
    }

    /**
     * Handle a network event.
     * <p>If no branch has been selected yet, the event selector can complete the first routing
     * decision here. Once a branch is selected, events are forwarded to that branch.</p>
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        this.checkAndExecutePendingUpgrade(context);

        if (this.selectedRoute == null && this.routing4Event != null) {
            String resolvedRoute = this.routing4Event.route(context, event, isRcv);
            if (resolvedRoute != null) {
                this.selectedRoute = resolvedRoute;
                this.activateBranchLifecycle(context, resolvedRoute, null, false);
            }
        }

        if (this.selectedRoute == null) {
            return true;
        }

        BranchEntry branch = this.branches.get(this.selectedRoute);
        if (branch != null) {
            if (isRcv) {
                branch.chainRoot.onRcvEvent(branch.branchCtx, null, event);
            } else {
                branch.chainRoot.onSndEvent(branch.branchCtx, null, event);
            }
        }
        this.checkAndExecutePendingUpgrade(context);
        return true;
    }

    /**
     * Handle data arriving at the routing node.
     * <p>The RCV direction is responsible for selecting a branch, advancing the branch receive
     * chain, and flushing the branch's RCV/SND outputs back to the parent chain.</p>
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
        this.checkAndExecutePendingUpgrade(context);

        if (isRcv) {
            ProtoStatus recoveryStatus = this.tryRecoverRcvBranch((ProtoContextService) context, rcvUp, rcvDown, sndDown);
            if (recoveryStatus != null) {
                return recoveryStatus;
            }

            if (this.routingMode == ProtoRoutingMode.REALTIME) {
                if (this.routing4Data == null) {
                    return ProtoStatus.Stop;
                }

                String resolvedRoute = this.routing4Data.route(context, rcvUp, sndDown);
                if (resolvedRoute == null) {
                    return ProtoStatus.Stop;
                }

                this.selectedRoute = resolvedRoute;
                this.activateBranchLifecycle(context, resolvedRoute, null, false);
            } else if (this.selectedRoute == null) {
                if (this.routing4Data == null) {
                    return ProtoStatus.Stop;
                }

                // Route not yet determined: pass the live rcvUp so the predicate can peek or partially consume.
                // Data not consumed by the predicate stays in rcvUp and will reappear on the next invocation.
                this.selectedRoute = this.routing4Data.route(context, rcvUp, sndDown);
                if (this.selectedRoute == null) {
                    // Predicate deferred — unconsumed data remains in rcvUp naturally.
                    return ProtoStatus.Stop;
                }
            }

            BranchEntry branch = this.branches.get(this.selectedRoute);
            if (branch == null) {
                return ProtoStatus.Stop;
            }

            // Route is now known. Fire branch onActive
            this.activateBranchLifecycle(context, this.selectedRoute, null, false);
            if (!this.flushBranchOutputs(branch, rcvDown, sndDown)) {
                this.checkAndExecutePendingUpgrade(context);
                return ProtoStatus.Next;
            }

            // Fast path: forward data to the selected branch.
            List<IN> data = rcvUp.takeMessage(rcvUp.queueSize());
            ProtoStatus status = this.doRcvRoute(branch, data, rcvDown, sndDown);
            if (status != ProtoStatus.Abort) {
                this.checkAndExecutePendingUpgrade(context);
            }
            return status;
        } else {
            ProtoStatus recoveryStatus = this.tryRecoverSndBranch((ProtoContextService) context, sndUp, sndDown);
            if (recoveryStatus != null) {
                return recoveryStatus;
            }

            ProtoStatus status = this.doSndRoute(context, sndUp, sndDown);
            if (status != ProtoStatus.Abort) {
                this.checkAndExecutePendingUpgrade(context);
            }
            return status;
        }
    }

    private ProtoStatus doSndRoute(ProtoContext context, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
        BranchEntry branch = this.branches.get(this.selectedRoute);
        if (branch != null && !this.flushBranchSndOutput(branch, sndDown)) {
            return ProtoStatus.Next;
        }

        if (!sndUp.hasMore()) {
            return ProtoStatus.Next;
        }

        if (this.selectedRoute == null) {
            // SND before route is determined — this is a pipeline logic error.
            int cnt = sndUp.queueSize();
            sndUp.takeMessage(cnt); // drain to prevent queue buildup
            logger.warn("[ROUTE] channel=" + context.getChannel().getChannelId() + " SND arrived before route was determined (" + cnt + " item(s) dropped)." + " This is a pipeline logic error — do not send data before the routing decision is made.");
            return ProtoStatus.Next;
        }

        branch = this.branches.get(this.selectedRoute);
        this.activateBranchLifecycle(context, this.selectedRoute, null, false);

        // Collect data from sndUp
        List<Object> sndData = sndUp.takeMessage(sndUp.queueSize());
        Object[] sndArray = sndData.toArray();

        // Execute branch SND chain
        ChainResult cr = branch.chainRoot.onSnd(branch.branchCtx, null, sndArray, null);

        // onSndMessage already drains headSndDown internally and returns the result directly.
        branch.addPendingSnd(cr.data);
        this.flushBranchSndOutput(branch, sndDown);

        if (cr.error != null) {
            throw cr.error;
        }
        return cr.status;
    }

    private ProtoStatus doRcvRoute(BranchEntry branch, List<IN> dataList, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<OUT> sndDown) throws Throwable {
        if (dataList.isEmpty()) {
            return ProtoStatus.Next;
        }

        Object[] rcvArray = dataList.toArray();
        // Execute branch RCV chain (which may also trigger branch SND chain internally)
        ChainResult cr = branch.chainRoot.onRcv(branch.branchCtx, null, rcvArray, null);
        branch.addPendingSnd(cr.data);
        this.flushBranchOutputs(branch, rcvDown, sndDown);

        if (cr.error != null) {
            throw cr.error;
        }
        return cr.status;
    }

    /**
     * Hand the current exception to the currently selected branch.
     * <p>The exception is forwarded to the selected branch's child pipeline only when a branch has
     * already been chosen; otherwise it skips the branch node and continues propagating.</p>
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (this.selectedRoute != null) {
            BranchEntry branch = this.branches.get(this.selectedRoute);
            ChainResult cr = isRcv ? //
                    branch.chainRoot.onRcv(branch.branchCtx, null, null, e) : //
                    branch.chainRoot.onSnd(branch.branchCtx, null, null, e);

            if (cr.error == null) {
                eh.clear();
            }
            return cr.status;
        }

        return ProtoStatus.Next;
    }

    /** Close all branch sub-pipelines. */
    @Override
    public void onClose(ProtoContext context) {
        this.clearSeedState();
        for (String name : this.branchOrder) {
            BranchEntry branch = this.branches.get(name);
            if (branch.chainRoot == null) {
                continue; // branch onInit failed before chainRoot was assigned — skip to avoid NPE
            }
            branch.chainRoot.onClose(branch.branchCtx);
        }
    }

    // recover

    private ProtoStatus tryRecoverRcvBranch(ProtoContextService context, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<OUT> sndDown) {
        BranchEntry recoveryBranch = this.recoveryBranch(context, true);
        if (recoveryBranch == null) {
            return null;
        }

        if (!this.flushBranchOutputs(recoveryBranch, rcvDown, sndDown)) {
            this.checkAndExecutePendingUpgrade(context);
            return ProtoStatus.Next;
        }

        if (!rcvUp.hasMore()) {
            this.checkAndExecutePendingUpgrade(context);
            return ProtoStatus.Next;
        }

        return null;
    }

    private ProtoStatus tryRecoverSndBranch(ProtoContextService context, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) {
        BranchEntry recoveryBranch = this.recoveryBranch(context, false);
        if (recoveryBranch == null) {
            return null;
        }

        if (!this.flushBranchSndOutput(recoveryBranch, sndDown)) {
            this.checkAndExecutePendingUpgrade(context);
            return ProtoStatus.Next;
        }

        if (!sndUp.hasMore()) {
            this.checkAndExecutePendingUpgrade(context);
            return ProtoStatus.Next;
        }

        return null;
    }

    private BranchEntry recoveryBranch(ProtoContextService context, boolean isRcv) {
        String recoveryRoute = this.currentRecoveryBranch(context, isRcv);
        if (recoveryRoute == null) {
            return null;
        }

        BranchEntry recoveryBranch = this.branches.get(recoveryRoute);
        if (recoveryBranch == null) {
            return null;
        }

        this.selectedRoute = recoveryRoute;
        return recoveryBranch;
    }

    // -- Builder --------------------------------------------------------

    /** Return whether a branch with the specified name has already been registered. */
    public boolean containsBranch(String name) {
        Objects.requireNonNull(name, "branch name is null.");
        return this.branches.containsKey(name);
    }

    /**
     * Register a new branch.
     * <p>Branches must be fully registered before the current routing duplexer is initialized.</p>
     * @param name branch name
     * @param initializer branch sub-pipeline initializer
     * @throws IllegalArgumentException if a branch with the same name already exists
     */
    public void addBranch(String name, ProtoInitializer initializer) {
        Objects.requireNonNull(name, "branch name is null.");
        Objects.requireNonNull(initializer, "initializer is null.");
        if (this.branches.containsKey(name)) {
            throw new IllegalArgumentException("Branch '" + name + "' already registered.");
        }
        this.branches.put(name, new BranchEntry(name, initializer));
        this.branchOrder.add(name);
    }

    /**
     * Preselect the branch that should be used for the current connection.
     * <p>This method only records the routing result; it does not immediately trigger
     * {@code onActive} on the target branch.</p>
     * @param name target branch name
     * @throws IllegalArgumentException if no branch with that name has been registered
     */
    public void activateBranch(String name) {
        Objects.requireNonNull(name, "branch name is null.");
        if (!this.branches.containsKey(name)) {
            throw new IllegalArgumentException("Unknown branch '" + name + "', available: " + this.branches.keySet());
        }

        this.selectedRoute = name;
    }

    private boolean flushBranchOutputs(BranchEntry branch, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<OUT> sndDown) {
        if (!this.flushBranchRcvOutput(branch.chainRoot, rcvDown, "ProtoRoutingDuplexer failed to flush branch receive output.")) {
            return false;
        }
        return this.flushBranchSndOutput(branch, sndDown);
    }

    private boolean flushBranchSndOutput(BranchEntry branch, ProtoSndQueue<OUT> sndDown) {
        int flushCount = Math.min(branch.pendingSnd.size(), sndDown.slotSize());
        if (flushCount <= 0) {
            return branch.pendingSnd.isEmpty();
        }
        for (int i = 0; i < flushCount; i++) {
            Object item = branch.pendingSnd.removeFirst();
            if (!sndDown.offerMessage((OUT) item)) {
                throw new IllegalStateException("ProtoRoutingDuplexer failed to flush branch send output.");
            }
        }
        return branch.pendingSnd.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private boolean flushBranchRcvOutput(ProtoStackChain chainRoot, ProtoSndQueue<Object> rcvDown, String errorMessage) {
        ProtoQueue<Object> branchTailRcvDown = (ProtoQueue<Object>) chainRoot.getTailRcvDown();
        int pendingSize = branchTailRcvDown.queueSize();
        if (pendingSize <= 0) {
            return true;
        }
        int flushCount = Math.min(pendingSize, rcvDown.slotSize());
        if (flushCount <= 0) {
            return false;
        }

        for (Object item : branchTailRcvDown.takeMessage(flushCount)) {
            if (!rcvDown.offerMessage(item)) {
                throw new IllegalStateException(errorMessage);
            }
        }

        if (branchTailRcvDown.wasFull()) {
            chainRoot.fireRcvRecover();
        }

        return branchTailRcvDown.queueSize() == 0;
    }

    private boolean shouldDelayPendingUpgrade(ProtoContext context) {
        if (this.selectedRoute == null) {
            return false;
        }
        BranchEntry branch = this.branches.get(this.selectedRoute);
        if (branch == null) {
            return false;
        }
        if (branch.hasPendingOutput()) {
            return true;
        }
        ProtoContextService ctx = (ProtoContextService) context;
        return ctx.hasRecovery(true, this.recoveryOwnerId, branch.branchName) || ctx.hasRecovery(false, this.recoveryOwnerId, branch.branchName);
    }

    private String currentRecoveryBranch(ProtoContextService context, boolean isRcv) {
        return context.activeRecovery(isRcv, this.recoveryOwnerId);
    }

    private void activateBranchLifecycle(ProtoContext context, String routeName, String fromRoute, boolean fireRouteChangedEvent) {
        if (context.getConfig().isPrintLog()) {
            logger.info("[ROUTE] channel=" + context.getChannel().getChannelId() + " selected='" + routeName + "'");
        }
        BranchEntry entry = this.branches.get(routeName);
        if (entry == null) {
            throw new IllegalStateException("Branch '" + routeName + "' not found, available: " + this.branches.keySet());
        }

        if (this.activatedBranches.add(routeName)) {
            try {
                entry.chainRoot.onActive(entry.branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + routeName + "' onActive error: " + e.getMessage(), e);
            }
            return;
        }

        if (!fireRouteChangedEvent) {
            return;
        }

        try {
            entry.chainRoot.onRcvEvent(entry.branchCtx, null, SoEventObject.of(context.getChannel(), ProtoRouteEvent.class, new ProtoRouteEvent(fromRoute, routeName)));
        } catch (Throwable e) {
            logger.error("Branch '" + routeName + "' route-change event error: " + e.getMessage(), e);
        }
    }

    /** Check for and execute any branch switch registered for the current round. */
    private void checkAndExecutePendingUpgrade(ProtoContext context) {
        if (this.pendingRoute == null) {
            return;
        }
        if (this.shouldDelayPendingUpgrade(context)) {
            return;
        }

        String newRoute = this.pendingRoute;
        this.pendingRoute = null;
        String oldRoute = this.selectedRoute;
        Object nextSeed = this.pendingRouteSeed;
        this.pendingRouteSeed = null;

        if (Objects.equals(oldRoute, newRoute)) {
            if (nextSeed != null) {
                this.releaseSeed(this.deliveredSeed);
                this.deliveredSeed = nextSeed;
            }
            return;
        }

        if (context.getConfig().isPrintLog()) {
            logger.info("[ROUTE] channel=" + context.getChannel().getChannelId() + " upgrading '" + oldRoute + "' → '" + newRoute + "'");
        }

        // Switch the active route.
        this.releaseSeed(this.deliveredSeed);
        this.selectedRoute = newRoute;
        this.deliveredSeed = nextSeed;
        this.activateBranchLifecycle(context, newRoute, oldRoute, true);
    }

    /** Return the child context associated with the specified branch. */
    ProtoContextService getBranchCtx(String branchName) {
        BranchEntry entry = this.branches.get(branchName);
        return entry != null ? entry.branchCtx : null;
    }

    private static final class BranchEntry {
        final String           branchName;
        final ProtoInitializer initializer;
        final Deque<Object>    pendingSnd;
        ProtoStackChain     chainRoot;
        ProtoContextService branchCtx;

        BranchEntry(String name, ProtoInitializer initializer) {
            this.branchName = name;
            this.initializer = initializer;
            this.pendingSnd = new ArrayDeque<>();
        }

        private void addPendingSnd(Object[] data) {
            if (data == null || data.length == 0) {
                return;
            }
            Collections.addAll(this.pendingSnd, data);
        }

        private boolean hasPendingOutput() {
            return !this.pendingSnd.isEmpty() || this.chainRoot.getTailRcvDown().queueSize() > 0;
        }
    }
}
