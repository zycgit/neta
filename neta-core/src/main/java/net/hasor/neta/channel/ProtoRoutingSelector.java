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
/**
 * Routing predicate used by {@link ProtoRoutingDuplexer} to choose a branch.
 * Evaluates pipeline state and/or inbound data to determine which sub-pipeline should handle
 * the current receive pass.
 * <p>
 * The routing decision is attempted in two phases:
 * <ol>
 *   <li><b>onActive phase</b> — called with {@link ProtoQueue#emptyRcv()} as {@code rcvUp}
 *       ({@code queueSize() == 0}, never null).
 *       This allows context-based routing (e.g. ALPN result from SSL handshake) to be resolved
 *       immediately at activation time, before any inbound data arrives.</li>
 *   <li><b>onMessage phase</b> — called with actual {@code rcvUp} queue during the first RCV
 *       invocation. This supports data-based routing (e.g. inspecting the first byte).</li>
 * </ol>
 * In {@link ProtoRoutingMode#STATIC} the selected route is cached until explicitly switched.
 * In {@link ProtoRoutingMode#REALTIME} the selector may be evaluated again on later inbound passes.
 * </p>
 * <p>
 * Routing decisions can be based on:
 * <ul>
 *   <li>Protocol context state — e.g. {@code context.context(SslContext.class)} for ALPN</li>
 *   <li>Data inspection — e.g. peeking at the first byte in rcvUp for TLS detection</li>
 *   <li>Any combination of context state and data</li>
 * </ul>
 * Note: routing does NOT require data to be present. For protocol-state-based routing
 * (such as SSL/ALPN or WebSocket sub-protocol), the decision is made from context alone.
 * </p>
 * @param <T> the data type flowing through the routing node
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@FunctionalInterface
public interface ProtoRoutingSelector<RCV_UP, SND_DOWN> {
    /**
     * Evaluate routing condition and return the selected branch key.
     * <p>
     * This method is called in two scenarios:
     * <ul>
     *   <li><b>During onActive</b>: {@code rcvUp} is {@link ProtoQueue#emptyRcv()} — an empty queue
     *       ({@code queueSize() == 0}, never null). Implementations should check
     *       {@code rcvUp.queueSize() == 0} and use only {@code context}
     *       for routing decisions (e.g. ALPN, pre-negotiated protocol state). Return {@code null}
     *       if data is required for the decision.</li>
     *   <li><b>During onMessage (RCV phase)</b>: {@code rcvUp} contains inbound data. Data should
     *       be inspected via {@code peekMessage()} but NOT consumed — the routing node will forward
     *       queued data to the selected branch after routing is determined.</li>
     * </ul>
     * </p>
     * @param context the pipeline context (use {@code context.context(Class)} to access protocol state)
     * @param rcvUp inbound data queue (upstream → this node), use {@code peekMessage()} for data-based routing.
     * <b>Never null.</b> During the onActive phase this is {@link ProtoQueue#emptyRcv()} ({@code queueSize() == 0});
     * check {@code rcvUp.queueSize() == 0} before calling {@code peekMessage()}.
     * @param sndDown the network-output queue for this pipeline level.
     * <b>Always non-null</b> in both the {@code onActive} and {@code onMessage} phases.
     * During {@code onActive}: backed by the parent pipeline's {@code headSndDown} — data
     * written here is sent directly to the network output of this pipeline level.
     * Router is designed to sit at the top of a pipeline, with each branch owning its own
     * complete Duplexer chain (ssl, frame encoders, etc.), so writing to this queue is safe
     * and does not bypass any branch-specific encoders.
     * During {@code onMessage}: same semantics — items flow to the downstream SND queue at
     * the Router's position in the parent pipeline.
     * @return the branch key matching a registered branch name, or null if routing cannot be determined yet
     */
    String route(ProtoContext context, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<SND_DOWN> sndDown);
}