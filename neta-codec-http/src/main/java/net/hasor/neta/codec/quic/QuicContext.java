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
package net.hasor.neta.codec.quic;

/**
 * Protocol context interface for QUIC transport connections.
 * <p>
 * QUIC provides the underlying transport for HTTP/3. This context exposes
 * QUIC transport–level state (connection ID, version, migration status)
 * independently of the HTTP/3 application layer.
 * </p>
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(QuicContext.class, impl)}.
 * </p>
 * <p>
 * Usage in {@link net.hasor.neta.channel.ProtoRouting}:
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     QuicContext quic = context.context(QuicContext.class);
 *     if (quic != null && quic.isReady()) {
 *         return "quic";
 *     }
 *     return null;
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface QuicContext {
    /** Returns {@code true} when the QUIC handshake is complete and 1-RTT keys are available. */
    boolean isReady();

    /** Returns {@code true} if this endpoint is the server side. */
    boolean isServer();

    /** Returns {@code true} if this endpoint is the client side. */
    boolean isClient();

    /** Returns the negotiated QUIC version (e.g. {@code 0x00000001} for QUIC v1). */
    int negotiatedVersion();

    /** Returns the peer's original destination connection ID as a hex string, or {@code null}. */
    String peerConnectionId();

    /** Returns the local connection ID as a hex string, or {@code null}. */
    String localConnectionId();

    /**
     * Returns the negotiated maximum number of bidirectional streams,
     * or -1 if not yet negotiated.
     */
    long maxBidiStreams();

    /**
     * Returns the negotiated maximum number of unidirectional streams,
     * or -1 if not yet negotiated.
     */
    long maxUniStreams();

    /** Returns the negotiated idle timeout in milliseconds, or -1 if not set. */
    long idleTimeout();
}
