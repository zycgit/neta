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
 * On the first RCV (inbound) onMessage invocation, the {@link ProtoRouting} predicate is
 * evaluated to select which branch should handle the connection. The routing decision is
 * cached for the connection's lifetime. Routing does NOT require data to be present —
 * decisions can be based purely on protocol context state (e.g. ALPN negotiation result
 * from {@code SslContext}).
 * </p>
 * <p>
 * Routing happens exclusively during the RCV phase because protocol handshakes
 * (SSL/TLS, ALPN, WebSocket upgrade, etc.) are completed during inbound processing.
 * SND (outbound) data arriving before route determination is passed through as-is.
 * </p>
 * <p>
 * Lifecycle events (onInit, onActive, onClose) are propagated to ALL branches.
 * Data events (onMessage) are routed only through the selected branch.
 * User events are propagated to ALL branches.
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
 * @param <T> the data type flowing into/out of the routing node
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public class ProtoRoutingDuplexer<T> implements ProtoDuplexer<T, Object, Object, T> {
    private static final Logger logger = Logger.getLogger(ProtoRoutingDuplexer.class);

    private final ProtoRouting<T>          routing;
    private final Map<String, BranchEntry> branches;
    private final List<String>             branchOrder;
    private       String                   selectedRoute;

    private ProtoRoutingDuplexer(ProtoRouting<T> routing, Map<String, BranchEntry> branches, List<String> branchOrder) {
        this.routing = Objects.requireNonNull(routing, "routing is null.");
        this.branches = branches;
        this.branchOrder = branchOrder;
    }

    /** Returns the currently selected route, or null if not yet determined. */
    public String getSelectedRoute() {
        return this.selectedRoute;
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        // Initialize ALL branches
        for (String name : this.branchOrder) {
            BranchEntry branch = this.branches.get(name);
            try {
                branch.initializer.config(branch.branchCtx);
                branch.chainRoot.onInit(branch.branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + name + "' onInit error: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void onActive(ProtoContext context, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<T> sndDown) throws Throwable {
        // Do NOT activate branches here — branch activation is deferred
        // until the route is determined during onMessage(isRcv=true).
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        // Propagate user events to ALL branches
        for (String name : this.branchOrder) {
            BranchEntry branch = this.branches.get(name);
            try {
                if (isRcv) {
                    branch.chainRoot.onRcvUserEvent(branch.branchCtx, null, event);
                } else {
                    branch.chainRoot.onSndUserEvent(branch.branchCtx, null, event);
                }
            } catch (Throwable e) {
                logger.error("Branch '" + name + "' onUserEvent error: " + e.getMessage(), e);
            }
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<T> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<T> sndDown) throws Throwable {
        if (isRcv) {
            // Route determination happens only during RCV phase — protocol handshakes
            // (SSL/ALPN, WebSocket, etc.) complete during inbound processing
            if (this.selectedRoute == null) {
                this.selectedRoute = this.routing.route(context, rcvUp, rcvDown);
                if (this.selectedRoute == null) {
                    return ProtoStatus.Stop; // routing cannot determine yet, wait for more RCV data
                }
                if (!this.branches.containsKey(this.selectedRoute)) {
                    throw new IllegalStateException("Routing returned unknown branch: '" + this.selectedRoute + "', available: " + this.branches.keySet());
                }

                // Activate the selected branch now that route is determined
                BranchEntry branch = this.branches.get(this.selectedRoute);
                Object[] activeData = branch.chainRoot.onActive(branch.branchCtx);

                // Write branch onActive data (e.g. SETTINGS frame) to sndDown immediately
                if (activeData != null && activeData.length > 0) {
                    for (Object obj : activeData) {
                        sndDown.offerMessage((T) obj);
                    }
                }
            }
            return this.doRcvRoute(context, rcvUp, rcvDown, sndDown);
        } else {
            return this.doSndRoute(context, sndUp, sndDown);
        }
    }

    @SuppressWarnings("unchecked")
    private ProtoStatus doRcvRoute(ProtoContext context, ProtoRcvQueue<T> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoSndQueue<T> sndDown) throws Throwable {
        if (!rcvUp.hasMore()) {
            return ProtoStatus.Next;
        }

        BranchEntry branch = this.branches.get(this.selectedRoute);

        // Collect data from rcvUp
        List<T> rcvData = rcvUp.takeMessage(rcvUp.queueSize());
        Object[] rcvArray = rcvData.toArray();

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
                sndDown.offerMessage((T) obj);
            }
        }

        return ProtoStatus.Next;
    }

    @SuppressWarnings("unchecked")
    private ProtoStatus doSndRoute(ProtoContext context, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<T> sndDown) throws Throwable {
        if (!sndUp.hasMore()) {
            return ProtoStatus.Next;
        }

        if (this.selectedRoute == null) {
            // SND before routing decision — should not happen in normal flow
            // pass through data as-is
            while (sndUp.hasMore()) {
                sndDown.offerMessage((T) sndUp.takeMessage());
            }
            return ProtoStatus.Next;
        }

        BranchEntry branch = this.branches.get(this.selectedRoute);

        // Collect data from sndUp
        List<Object> sndData = sndUp.takeMessage(sndUp.queueSize());
        Object[] sndArray = sndData.toArray();

        // Execute branch SND chain
        Object[] sndResult = branch.chainRoot.onSndMessage(branch.branchCtx, null, sndArray);

        // Collect SND output from branch headSndDown
        ProtoQueue<Object> branchHeadSndDown = (ProtoQueue<Object>) branch.chainRoot.getHeadSndDown();
        if (branchHeadSndDown.queueSize() > 0) {
            List<Object> branchSndOutput = branchHeadSndDown.takeMessage(branchHeadSndDown.queueSize());
            branchHeadSndDown.rcvSubmit();
            for (Object obj : branchSndOutput) {
                sndDown.offerMessage((T) obj);
            }
        }

        // Also forward any returned data
        if (sndResult != null) {
            for (Object obj : sndResult) {
                sndDown.offerMessage((T) obj);
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    @SuppressWarnings("unchecked")
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
        // Close ALL branches
        for (String name : this.branchOrder) {
            BranchEntry branch = this.branches.get(name);
            try {
                branch.chainRoot.onClose(branch.branchCtx);
            } catch (Throwable e) {
                logger.error("Branch '" + name + "' onClose error: " + e.getMessage(), e);
            }
        }
    }

    // -- Builder --------------------------------------------------------

    /**
     * Create a new builder for constructing a {@link ProtoRoutingDuplexer}.
     * @param routing the routing predicate (receives full onMessage parameters)
     * @param <T> the data type flowing through the routing node
     * @return the builder
     */
    public static <T> Builder<T> newBuilder(ProtoRouting<T> routing) {
        return new Builder<>(routing);
    }

    /**
     * Builder for constructing a {@link ProtoRoutingDuplexer} with registered branches.
     * @param <T> the data type flowing through the routing node
     */
    public static class Builder<T> {
        private final ProtoRouting<T>               routing;
        private final Map<String, ProtoInitializer> branchInitializers;
        private final List<String>                  branchOrder;

        Builder(ProtoRouting<T> routing) {
            this.routing = Objects.requireNonNull(routing, "routing is null.");
            this.branchInitializers = new LinkedHashMap<>();
            this.branchOrder = new ArrayList<>();
        }

        /**
         * Register a branch with the given name and pipeline initializer.
         * @param name the branch name (must match the routing predicate return value)
         * @param initializer a ProtoInitializer that configures the branch pipeline
         * @return this builder
         */
        public Builder<T> branch(String name, ProtoInitializer initializer) {
            Objects.requireNonNull(name, "branch name is null.");
            Objects.requireNonNull(initializer, "initializer is null.");
            if (this.branchInitializers.containsKey(name)) {
                throw new IllegalArgumentException("Branch '" + name + "' already registered.");
            }
            this.branchInitializers.put(name, initializer);
            this.branchOrder.add(name);
            return this;
        }

        /**
         * Build the {@link ProtoRoutingDuplexer}. Must be called within a pipeline initialization context
         * so that the context's channel and soContext are available for branch creation.
         * @param parentContext the parent pipeline context
         * @return the constructed routing duplexer
         */
        public ProtoRoutingDuplexer<T> build(ProtoContext parentContext) {
            if (this.branchInitializers.isEmpty()) {
                throw new IllegalStateException("At least one branch must be registered.");
            }

            Map<String, BranchEntry> branches = new LinkedHashMap<>();
            for (Map.Entry<String, ProtoInitializer> entry : this.branchInitializers.entrySet()) {
                String name = entry.getKey();
                ProtoInitializer init = entry.getValue();

                // Create branch-mode ProtoContextService sharing contextData/flash/namedHandlerMap with parent
                ProtoContextService branchCtx = new ProtoContextService((ProtoContextService) parentContext, -1, -1);
                ProtoChainRoot chainRoot = branchCtx.getChainRoot();

                branches.put(name, new BranchEntry(name, chainRoot, branchCtx, init));
            }

            return new ProtoRoutingDuplexer<>(this.routing, branches, new ArrayList<>(this.branchOrder));
        }
    }

    /** Internal branch entry holding the sub-pipeline chain. */
    static class BranchEntry {
        final String              name;
        final ProtoChainRoot      chainRoot;
        final ProtoContextService branchCtx;
        final ProtoInitializer    initializer;

        BranchEntry(String name, ProtoChainRoot chainRoot, ProtoContextService branchCtx, ProtoInitializer initializer) {
            this.name = name;
            this.chainRoot = chainRoot;
            this.branchCtx = branchCtx;
            this.initializer = initializer;
        }
    }
}
