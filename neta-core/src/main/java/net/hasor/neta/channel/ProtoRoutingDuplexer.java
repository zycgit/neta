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
 * A routing duplexer that forks the pipeline into multiple sub-pipeline branches.
 * <p>
 * Routing is attempted in up to three phases:
 * <ol>
 *   <li><b>onActive</b>: called with an empty {@code rcvUp} — supports context-based data routing (e.g. ALPN).</li>
 *   <li><b>onMessage (first RCV)</b>: called with real inbound data — supports data-based routing (e.g. TLS peek).</li>
 *   <li><b>onUserEvent</b>: optionally called with an inbound/outbound user event while no branch is selected yet.</li>
 * </ol>
 * The predicate may peek/take from {@code rcvUp}; unconsumed data is re-presented on the next call.
 * Return {@code null} to defer; once a branch key is returned the decision is cached for the connection's lifetime.
 * </p>
 * <p>
 * Lifecycle: {@code onInit}/{@code onClose} → ALL branches;
 * {@code onActive}/{@code onMessage}/user-events → selected branch only.
 * SND data arriving before the route is determined is dropped with a warning.
 * </p>
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

    public ProtoRoutingDuplexer(ProtoRoutingDataSelector<IN, OUT> routing) {
        this(ProtoRoutingMode.STATIC, routing);
    }

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
            public ProtoRoutingMode getMode() {
                return routingMode;
            }

            @Override
            public String currentRoute() {
                return selectedRoute;
            }

            @Override
            public void switchRoute(String newBranchName) {
                schedulePendingUpgrade(newBranchName);
            }
        };
    }

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
            branchCtx.context(ProtoRoutingControl.class, this.routingControl);

            // Run the user-supplied initializer to populate the branch handler chain
            entry.branchCtx = branchCtx;
            entry.chainRoot = chainRoot;
            entry.initializer.config(branchCtx);
            chainRoot.onInit(branchCtx);
        }
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        if (this.routingMode == ProtoRoutingMode.REALTIME) {
            this.activateAllBranches(context);
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

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
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
                branch.chainRoot.onRcvUserEvent(branch.branchCtx, null, event);
            } else {
                branch.chainRoot.onSndUserEvent(branch.branchCtx, null, event);
            }
        }
        this.checkAndExecutePendingUpgrade(context);
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
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
            this.checkAndExecutePendingUpgrade(context);
            return status;
        } else {
            ProtoStatus recoveryStatus = this.tryRecoverSndBranch((ProtoContextService) context, sndUp, sndDown);
            if (recoveryStatus != null) {
                return recoveryStatus;
            }

            ProtoStatus status = this.doSndRoute(context, sndUp, sndDown);
            this.checkAndExecutePendingUpgrade(context);
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

    @Override
    public void onClose(ProtoContext context) {
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

    /** Returns {@code true} if a branch named {@code name} has already been registered. */
    public boolean containsBranch(String name) {
        Objects.requireNonNull(name, "branch name is null.");
        return this.branches.containsKey(name);
    }

    /**
     * Registers a new branch with the given {@code name} and {@code initializer}.
     * Must be called before {@link ProtoDuplexer#onInit} — adding branches after initialisation is not supported.
     * @throws IllegalArgumentException if a branch with that name already exists
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
     * Pre-selects the routing branch for this connection by name, as an imperative alternative to
     * returning a branch name from {@link ProtoRoutingDataSelector#route}.
     * <p>
     * This method <em>only</em> records the routing decision ({@code selectedRoute = name}).
     * It can be called at any time — even before {@code onInit} — and does <em>not</em> trigger
     * the branch's {@code onActive} lifecycle. The actual {@code onActive} is always fired at the
     * normal lifecycle point: when the Router's own {@code onActive} runs, or on the first inbound
     * message, whichever comes first.
     * </p>
     * @throws IllegalArgumentException if no branch with that name has been registered
     */
    public void activateBranch(String name) {
        Objects.requireNonNull(name, "branch name is null.");
        if (!this.branches.containsKey(name)) {
            throw new IllegalArgumentException("Unknown branch '" + name + "', available: " + this.branches.keySet());
        }
        this.selectedRoute = name;
    }

    private void activateAllBranches(ProtoContext context) {
        for (String branchName : this.branchOrder) {
            this.activateBranchLifecycle(context, branchName, null, false);
        }
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
            entry.chainRoot.onRcvUserEvent(entry.branchCtx, null, SoUserEventObject.of(context.getChannel(), ProtoRouteEvent.class, new ProtoRouteEvent(fromRoute, routeName)));
        } catch (Throwable e) {
            logger.error("Branch '" + routeName + "' route-change event error: " + e.getMessage(), e);
        }
    }

    /** Records a pending protocol-upgrade request. */
    void schedulePendingUpgrade(String newBranchName) {
        if (!this.branches.containsKey(newBranchName)) {
            throw new IllegalArgumentException("Unknown branch '" + newBranchName + "' for upgrade, available: " + this.branches.keySet());
        }
        this.pendingRoute = newBranchName;
    }

    /** Executes the pending protocol upgrade if one was scheduled during the current pipeline pass. */
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

        if (Objects.equals(oldRoute, newRoute)) {
            return;
        }

        if (context.getConfig().isPrintLog()) {
            logger.info("[ROUTE] channel=" + context.getChannel().getChannelId() + " upgrading '" + oldRoute + "' → '" + newRoute + "'");
        }

        // Switch the active route.
        this.selectedRoute = newRoute;
        this.activateBranchLifecycle(context, newRoute, oldRoute, true);
    }

    ProtoContextService getBranchCtx(String branchName) {
        BranchEntry entry = this.branches.get(branchName);
        return entry != null ? entry.branchCtx : null;
    }

    private final class BranchEntry {
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

        @SuppressWarnings("unchecked")
        private boolean hasPendingOutput() {
            return !this.pendingSnd.isEmpty() || this.chainRoot.getTailRcvDown().queueSize() > 0;
        }
    }
}
