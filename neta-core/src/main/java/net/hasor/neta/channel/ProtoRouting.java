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
 * Routing predicate for branching pipeline. Evaluates pipeline state and/or
 * incoming data to determine which sub-pipeline branch should handle the connection.
 * <p>
 * The routing decision is made during the RCV (inbound) phase only — typically once
 * on the first inbound onMessage invocation — and cached for the lifetime of the connection.
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
public interface ProtoRouting<T> {
    /**
     * Evaluate routing condition and return the selected branch key.
     * <p>
     * This method is called only during the RCV (inbound) phase. Data in the queue
     * should be inspected (peek) but NOT consumed — the routing node will forward
     * queued data to the selected branch after routing is determined.
     * </p>
     * @param context the pipeline context (use {@code context.context(Class)} to access protocol state)
     * @param rcvUp inbound data queue (upstream → this node), use {@code peekMessage()} for data-based routing
     * @param rcvDown outbound response queue (this node → downstream), use to send protocol negotiation
     * responses (e.g. HTTP 101 upgrade) before the branch is selected
     * @return the branch key matching a registered branch name, or null if routing cannot be determined yet
     */
    String route(ProtoContext context, ProtoRcvQueue<T> rcvUp, ProtoSndQueue<Object> rcvDown);
}