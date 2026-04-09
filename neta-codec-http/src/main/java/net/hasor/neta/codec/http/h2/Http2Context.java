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
package net.hasor.neta.codec.http.h2;

/**
 * Protocol context interface for an HTTP/2 connection.
 * <p>
 * This is a connection-level read-only state view used to expose the current HTTP/2 connection's
 * negotiated settings and flow-control state to routing decisions and downstream handlers.
 * <p>
 * The current implementation installs the shared instance into the root context during HTTP/2
 * decoder initialization and then exposes that same instance through the current
 * {@link net.hasor.neta.channel.ProtoContext}. It therefore represents connection-level state,
 * not local state owned by a single stream or branch.
 * </p>
 * <p>
 * When connection-level state must be read across branches, prefer accessing it from the root
 * context. If the current node already runs inside the same HTTP/2 branch, it may also be read
 * directly from the current context.
 * </p>
 * <p>
 * In a {@link net.hasor.neta.channel.ProtoRoutingDataSelector} used by
 * {@link net.hasor.neta.channel.ProtoRoutingDuplexer}, a typical access pattern looks like this:
 * <pre>{@code
 * (context, rcvUp, sndDown) -> {
 *     Http2Context h2 = context.rootContext(Http2Context.class);
 *     if (h2 != null && h2.isReady()) {
 *         return "http2-stream";
 *     }
 *     return null; // Defer routing.
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
public interface Http2Context {
    /**
     * Returns {@code true} once the connection has received the peer preface.
     */
    boolean isReady();

    /**
     * Returns {@code true} if the current endpoint is the server side.
     */
    boolean isServer();

    /**
     * Returns {@code true} if the current endpoint is the client side.
     */
    boolean isClient();

    /**
     * Returns the largest stream ID currently being tracked.
     */
    long lastStreamId();

    /**
     * Returns the currently effective remote SETTINGS_MAX_CONCURRENT_STREAMS value.
     * The initial value is {@link Long#MAX_VALUE}.
     */
    long maxConcurrentStreams();

    /**
     * Returns the negotiated remote SETTINGS_INITIAL_WINDOW_SIZE value.
     * If negotiation has not completed yet, the default value 65535 is returned.
     */
    int initialWindowSize();

    /**
     * Returns the negotiated remote SETTINGS_MAX_FRAME_SIZE value.
     * If negotiation has not completed yet, the default value 16384 is returned.
     */
    int maxFrameSize();
}
