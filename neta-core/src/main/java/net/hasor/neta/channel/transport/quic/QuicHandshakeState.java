/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Handshake lifecycle states exposed by the QUIC stack.
 * <p><b>State machine:</b>
 * <pre>
 *   INITIAL  -->  HANDSHAKE  -->  ESTABLISHED
 *      \                           /
 *       +---------> CLOSED &lt;-------+
 * </pre>
 * <p>This enum is a simplified projection of the internal {@link QuicAsyncChannelHandshake} state, used to describe
 * the current key phase of the connection and whether application data can already be sent and received; it does not
 * cover every packet-level detail of the wire protocol.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicFrameType
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001 — Using TLS to Protect QUIC</a>
 */
public enum QuicHandshakeState {
    /** Initial state before any handshake messages have been exchanged. */
    INITIAL,
    /** Handshake in progress, with Initial or Handshake level packets already being exchanged. */
    HANDSHAKE,
    /** Handshake completed, so 1-RTT application data can be sent. */
    ESTABLISHED,
    /** Connection is closed. */
    CLOSED
}
