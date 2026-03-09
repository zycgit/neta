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
package net.hasor.neta.channel.quic;

/**
 * Public view of the handshake lifecycle reported by the QUIC stack.
 * <p><b>State machine:</b>
 * <pre>
 *   INITIAL  -->  HANDSHAKE  -->  ESTABLISHED
 *      \                           /
 *       +---------> CLOSED <-------+
 * </pre>
 * <p>The enum is a simplified projection of the internal
 * {@link QuicAsyncChannelHandshake} state. It indicates which key phase the
 * connection has reached and whether application traffic can already flow; it
 * is not a full description of every frame type that may appear on the wire.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicFrameType
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001 — Using TLS to Secure QUIC</a>
 */
public enum QuicHandshakeState {
    /** Initial state; no handshake messages exchanged yet. */
    INITIAL,
    /** Handshake is in progress (Initial + Handshake packets exchanged). */
    HANDSHAKE,
    /** Handshake fully completed; 1-RTT application data may flow. */
    ESTABLISHED,
    /** Connection has been closed. */
    CLOSED
}
