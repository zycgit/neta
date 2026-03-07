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
 * Routing is attempted in two phases via {@link ProtoRoutingSelector#route}:
 * <ol>
 *   <li><b>onActive</b>: called with an empty {@code rcvUp} — supports context-based routing (e.g. ALPN).</li>
 *   <li><b>onMessage (first RCV)</b>: called with real inbound data — supports data-based routing (e.g. TLS peek).</li>
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
    private static final Logger                        logger = Logger.getLogger(ProtoRoutingDuplexer.class);
    private final        ProtoRoutingSelector<IN, OUT> routing;
    private final        Map<String, BranchEntry>      branches;
    private final        List<String>                  branchOrder;
    private              String                        selectedRoute;
    private              boolean                       branchActivated;
    private              String                        pendingRoute;    // non-null during a pending protocol upgrade; cleared once the switch executes

    public ProtoRoutingDuplexer(ProtoRoutingSelector<IN, OUT> routing) {
        this.routing = Objects.requireNonNull(routing, "routing is null.");
        this.branches = new LinkedHashMap<>();
        this.branchOrder = new ArrayList<>();
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
            ProtoStackChain chainRoot = branchCtx.getChainRoot();
            branchCtx.setOwnerRouter(this);

            // Run the user-supplied initializer to populate the branch handler chain
            entry.branchCtx = branchCtx;
            entry.chainRoot = chainRoot;
            entry.initializer.config(branchCtx);
            chainRoot.onInit(branchCtx);
        }
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        if (this.selectedRoute == null) {
            // No pre-selection: ask the routing selector (context-based, e.g. ALPN)
            ProtoSndQueue<?> headSndDown = ((ProtoContextService) context).getChainRoot().getHeadSndDown();
            this.selectedRoute = this.routing.route(context, ProtoQueue.emptyRcv(), (ProtoSndQueue<OUT>) headSndDown);
        }
        if (this.selectedRoute != null) {
            this.doActivateSelectedBranch(context);
        }
        // If selectedRoute is still null, branch activation is deferred to onMessage(isRcv=true)
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
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
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<IN> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
        if (isRcv) {
            if (this.selectedRoute == null) {
                // Route not yet determined: pass the live rcvUp so the predicate can peek or partially consume.
                // Data not consumed by the predicate stays in rcvUp and will reappear on the next invocation.
                this.selectedRoute = this.routing.route(context, rcvUp, sndDown);
                if (this.selectedRoute == null) {
                    // Predicate deferred — unconsumed data remains in rcvUp naturally.
                    return ProtoStatus.Stop;
                }
            }

            // Route is now known. Fire branch onActive
            if (!this.branchActivated) {
                this.doActivateSelectedBranch(context);
            }

            // Fast path: forward data to the selected branch.
            List<IN> data = rcvUp.takeMessage(rcvUp.queueSize());
            ProtoStatus status = this.doRcvRoute(this.branches.get(this.selectedRoute), data, rcvDown, sndDown);
            this.checkAndExecutePendingUpgrade(context);
            return status;
        } else {
            ProtoStatus status = this.doSndRoute(context, sndUp, sndDown);
            this.checkAndExecutePendingUpgrade(context);
            return status;
        }
    }

    private ProtoStatus doSndRoute(ProtoContext context, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<OUT> sndDown) throws Throwable {
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

        BranchEntry branch = this.branches.get(this.selectedRoute);

        // Collect data from sndUp
        List<Object> sndData = sndUp.takeMessage(sndUp.queueSize());
        Object[] sndArray = sndData.toArray();

        // Execute branch SND chain
        ChainResult cr = branch.chainRoot.onSnd(branch.branchCtx, null, sndArray, null);

        // onSndMessage already drains headSndDown internally and returns the result directly.
        for (Object obj : cr.data) {
            sndDown.offerMessage((OUT) obj);
        }

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

        // Collect RCV output from branch tailRcvDown
        ProtoQueue<Object> branchTailRcvDown = (ProtoQueue<Object>) branch.chainRoot.getTailRcvDown();
        if (branchTailRcvDown.queueSize() > 0) {
            List<Object> branchRcvOutput = branchTailRcvDown.takeMessage(branchTailRcvDown.queueSize());
            branchTailRcvDown.rcvSubmit();
            rcvDown.offerMessage(branchRcvOutput);
        }

        // Forward branch SND results to main pipeline sndDown
        for (Object obj : cr.data) {
            sndDown.offerMessage((OUT) obj);
        }

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
     * returning a branch name from {@link ProtoRoutingSelector#route}.
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

        if (this.branchActivated) {
            throw new IllegalStateException("branch already activated for route '" + this.selectedRoute + "'");
        }

        this.selectedRoute = name;
    }

    /**
     * Fires {@link ProtoStackChain#onActive} on the selected branch exactly once.
     * Guarded by {@link #branchActivated} — subsequent calls are no-ops.
     */
    private void doActivateSelectedBranch(ProtoContext context) {
        if (this.branchActivated) {
            return;
        }

        this.branchActivated = true;
        if (context.getConfig().isPrintLog()) {
            logger.info("[ROUTE] channel=" + context.getChannel().getChannelId() + " selected='" + this.selectedRoute + "'");
        }
        BranchEntry entry = this.branches.get(this.selectedRoute);
        if (entry == null) {
            throw new IllegalStateException("Branch '" + this.selectedRoute + "' not found, available: " + this.branches.keySet());
        }

        try {
            entry.chainRoot.onActive(entry.branchCtx);
        } catch (Throwable e) {
            logger.error("Branch '" + this.selectedRoute + "' onActive error: " + e.getMessage(), e);
        }
    }

    /** Records a pending protocol-upgrade request. Called by {@link ProtoContextService#upgradeRoute} from within a branch handler. The actual switch is deferred to the end of the current */
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
        String newRoute = this.pendingRoute;
        this.pendingRoute = null;

        if (context.getConfig().isPrintLog()) {
            logger.info("[ROUTE] channel=" + context.getChannel().getChannelId() + " upgrading '" + this.selectedRoute + "' → '" + newRoute + "'");
        }

        // Switch the active route.
        this.selectedRoute = newRoute;

        // Activate new branch (branchActivated stays true; we call onActive directly).
        BranchEntry newBranch = this.branches.get(newRoute);
        if (newBranch != null && newBranch.chainRoot != null) {
            try {
                newBranch.chainRoot.onActive(newBranch.branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + newRoute + "' onActive error (upgrade): " + e.getMessage(), e);
            }
        }
    }

    ProtoContextService getBranchCtx(String branchName) {
        BranchEntry entry = this.branches.get(branchName);
        return entry != null ? entry.branchCtx : null;
    }

    private static class BranchEntry {
        final String           name;
        final ProtoInitializer initializer;
        ProtoStackChain     chainRoot;
        ProtoContextService branchCtx;

        BranchEntry(String name, ProtoInitializer initializer) {
            this.name = name;
            this.initializer = initializer;
        }
    }
}
