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
package net.hasor.neta.codec.spdy;

/**
 * Protocol context interface for SPDY connections.
 * <p>
 * SPDY is a deprecated protocol superseded by HTTP/2. This context interface
 * is provided for backward–compatible environments that still negotiate SPDY
 * via ALPN/NPN. It exposes SPDY session–level state.
 * </p>
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(SpdyContext.class, impl)}.
 * </p>
 * <p>
 * Usage in {@link net.hasor.neta.channel.ProtoRouting}:
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     SpdyContext spdy = context.context(SpdyContext.class);
 *     if (spdy != null && spdy.isReady()) {
 *         return "spdy/" + spdy.version();
 *     }
 *     return null;
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface SpdyContext {
    /** Returns {@code true} when the SPDY session setup (SYN_STREAM / SYN_REPLY handshake) is complete. */
    boolean isReady();

    /** Returns {@code true} if this endpoint is the server side. */
    boolean isServer();

    /** Returns {@code true} if this endpoint is the client side. */
    boolean isClient();

    /** Returns the SPDY version (e.g. 2 or 3). */
    int version();

    /** Returns the last stream ID created by this endpoint. */
    int lastStreamId();

    /**
     * Returns the negotiated maximum number of concurrent streams,
     * or -1 if not yet negotiated.
     */
    int maxConcurrentStreams();
}
