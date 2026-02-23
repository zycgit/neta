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
package net.hasor.neta.codec.http.h3;
/**
 * Protocol context interface for HTTP/3 connections.
 * <p>
 * HTTP/3 runs over QUIC. This context exposes HTTP/3–specific state
 * such as QPACK settings and stream information. Registered on
 * {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(Http3Context.class, impl)}.
 * </p>
 * <p>
 * Usage in {@link net.hasor.neta.channel.ProtoRouting}:
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     Http3Context h3 = context.context(Http3Context.class);
 *     if (h3 != null && h3.isReady()) {
 *         return "http3-stream";
 *     }
 *     return null;
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface Http3Context {
    /** Returns {@code true} when the HTTP/3 SETTINGS frame exchange is complete. */
    boolean isReady();

    /** Returns {@code true} if this endpoint is the server side. */
    boolean isServer();

    /** Returns {@code true} if this endpoint is the client side. */
    boolean isClient();

    /** Returns the last stream ID observed or created by this endpoint. */
    long lastStreamId();

    /**
     * Returns the SETTINGS_MAX_FIELD_SECTION_SIZE value from the remote peer,
     * or -1 if not yet negotiated.
     */
    long maxFieldSectionSize();

    /**
     * Returns the QPACK_MAX_TABLE_CAPACITY value from the remote peer,
     * or -1 if not yet negotiated.
     */
    long qpackMaxTableCapacity();

    /**
     * Returns the QPACK_BLOCKED_STREAMS value from the remote peer,
     * or -1 if not yet negotiated.
     */
    long qpackBlockedStreams();
}
