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

    public ProtoRoutingDuplexer(ProtoRoutingSelector<IN, OUT> routing) {
        this.routing = Objects.requireNonNull(routing, "routing is null.");
        this.branches = new LinkedHashMap<>();
        this.branchOrder = new ArrayList<>();
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        if (this.branchOrder.isEmpty()) {
            throw new IllegalStateException("ProtoRoutingDuplexer has no branches registered. At least one branch is required.");
        }
        // Pre-compute the adjacent-node names once; they are constant for the lifetime of the
        // connection and shared by all branches of this Router.
        ProtoContextService parentCtxService = (ProtoContextService) context;
        String routerName = context.getStackName();
        String prevStackName = parentCtxService.getChainRoot().findPreviousStack(routerName);
        String nextStackName = parentCtxService.getChainRoot().findNextStack(routerName);

        // Lazily create and initialize each branch context (branch pipelines are built here, not at addBranch time).
        for (String name : this.branchOrder) {
            BranchEntry entry = this.branches.get(name);
            ProtoContextService branchCtx = new ProtoContextService(parentCtxService, -1, -1, prevStackName, nextStackName);
            ProtoChainRoot chainRoot = branchCtx.getChainRoot();

            // Run the user-supplied initializer to populate the branch handler chain
            entry.initializer.config(branchCtx);
            // Start the branch chain lifecycle
            try {
                chainRoot.onInit(branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + name + "' onInit error: " + e.getMessage(), e);
                throw e;
            }
            // Store back the fully-initialized entry (replaces the null-chainRoot placeholder)
            this.branches.put(name, new BranchEntry(name, chainRoot, branchCtx, entry.initializer));
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

            // Route is now known. Fire branch onActive if not yet done (covers the case where
            // activateBranch() was called before onActive, or selector decided on first message).
            if (!this.branchActivated) {
                this.doActivateSelectedBranch(context);
            }

            // Fast path: forward data to the selected branch.
            List<IN> data = rcvUp.takeMessage(rcvUp.queueSize());
            return this.doRcvRoute(this.branches.get(this.selectedRoute), data, rcvDown, sndDown);
        } else {
            return this.doSndRoute(context, sndUp, sndDown);
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
        Object[] sndResult = branch.chainRoot.onSndMessage(branch.branchCtx, null, sndArray);

        // onSndMessage already drains headSndDown internally and returns the result directly.
        if (sndResult != null) {
            for (Object obj : sndResult) {
                sndDown.offerMessage((OUT) obj);
            }
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus doRcvRoute(BranchEntry branch, List<IN> dataList, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<OUT> sndDown) throws Throwable {
        if (dataList.isEmpty()) {
            return ProtoStatus.Next;
        }

        Object[] rcvArray = dataList.toArray();
        // Execute branch RCV chain (which may also trigger branch SND chain internally)
        Object[] sndResult = branch.chainRoot.onRcvMessage(branch.branchCtx, null, rcvArray);

        // Collect RCV output from branch tailRcvDown
        ProtoQueue<Object> branchTailRcvDown = (ProtoQueue<Object>) branch.chainRoot.getTailRcvDown();
        if (branchTailRcvDown.queueSize() > 0) {
            List<Object> branchRcvOutput = branchTailRcvDown.takeMessage(branchTailRcvDown.queueSize());
            branchTailRcvDown.rcvSubmit();
            rcvDown.offerMessage(branchRcvOutput);
        }

        // Forward branch SND results to main pipeline sndDown
        if (sndResult != null) {
            for (Object obj : sndResult) {
                sndDown.offerMessage((OUT) obj);
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (this.selectedRoute != null) {
            BranchEntry branch = this.branches.get(this.selectedRoute);
            try {
                if (isRcv) {
                    branch.chainRoot.onRcvError(branch.branchCtx, null, e);
                } else {
                    branch.chainRoot.onSndError(branch.branchCtx, null, e);
                }
                eh.clear();
            } catch (Throwable ex) {
                // branch error handling failed, propagate original error
            }
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
            try {
                branch.chainRoot.onClose(branch.branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + name + "' onClose error: " + e.getMessage(), e);
            }
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
     * Must be called before {@link #onInit} — adding branches after initialisation is not supported.
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
     * Fires {@link ProtoChainRoot#onActive} on the selected branch exactly once.
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

    /** Returns the branch ctx for the named branch. */
    ProtoContextService getBranchCtx(String branchName) {
        BranchEntry entry = this.branches.get(branchName);
        return entry != null ? entry.branchCtx : null;
    }

    /** Internal branch entry holding the sub-pipeline chain. */
    private static class BranchEntry {
        final String              name;
        final ProtoChainRoot      chainRoot;
        final ProtoContextService branchCtx;
        final ProtoInitializer    initializer;

        BranchEntry(String name, ProtoInitializer initializer) {
            this(name, null, null, initializer);
        }

        BranchEntry(String name, ProtoChainRoot chainRoot, ProtoContextService branchCtx, ProtoInitializer initializer) {
            this.name = name;
            this.chainRoot = chainRoot;
            this.branchCtx = branchCtx;
            this.initializer = initializer;
        }
    }
}
