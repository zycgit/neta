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
 * Protocol context interface for HTTP/2 connections.
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(Http2Context.class, impl)} to expose HTTP/2 state
 * to routing decisions and downstream handlers.
 * </p>
 * <p>
 * Usage in {@link net.hasor.neta.channel.ProtoRouting}:
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     Http2Context h2 = context.context(Http2Context.class);
 *     if (h2 != null && h2.isReady()) {
 *         return "http2-stream";
 *     }
 *     return null; // defer routing
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface Http2Context {
    /** Returns {@code true} when the HTTP/2 connection preface and SETTINGS exchange are complete. */
    boolean isReady();

    /** Returns {@code true} if this endpoint is the server side. */
    boolean isServer();

    /** Returns {@code true} if this endpoint is the client side. */
    boolean isClient();

    /** Returns the last stream ID observed or created by this endpoint. */
    int lastStreamId();

    /**
     * Returns the negotiated SETTINGS_MAX_CONCURRENT_STREAMS value from the remote peer,
     * or -1 if not yet negotiated.
     */
    long maxConcurrentStreams();

    /**
     * Returns the negotiated SETTINGS_INITIAL_WINDOW_SIZE value from the remote peer,
     * or the default (65535) if not yet negotiated.
     */
    int initialWindowSize();

    /**
     * Returns the negotiated SETTINGS_MAX_FRAME_SIZE value from the remote peer,
     * or the default (16384) if not yet negotiated.
     */
    int maxFrameSize();
}
