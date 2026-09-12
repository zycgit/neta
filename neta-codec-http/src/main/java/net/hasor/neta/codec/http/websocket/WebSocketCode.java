/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Standard websocket close status code definitions.
 * <p>
 * Collects the constants used by close frames and protocol-violation handling,
 * primarily from RFC 6455 section 7.4.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public final class WebSocketCode {
    /** {@code 1000}, normal closure. */
    public static final int NORMAL_CLOSURE      = 1000;
    /** {@code 1001}, endpoint is going away. */
    public static final int GOING_AWAY          = 1001;
    /** {@code 1002}, protocol error. */
    public static final int PROTOCOL_ERROR      = 1002;
    /** {@code 1003}, unsupported data type. */
    public static final int UNSUPPORTED_DATA    = 1003;
    /** {@code 1004}, reserved by RFC 6455 and must not be sent on the wire. */
    public static final int RESERVED            = 1004;
    /** {@code 1005}, no status received, locally synthesized only, never sent on the wire. */
    public static final int NO_STATUS           = 1005;
    /** {@code 1006}, abnormal closure, locally synthesized only, never sent on the wire. */
    public static final int ABNORMAL_CLOSURE    = 1006;
    /** {@code 1007}, invalid frame payload data. */
    public static final int INVALID_DATA        = 1007;
    /** {@code 1008}, policy violation. */
    public static final int POLICY_VIOLATION    = 1008;
    /** {@code 1009}, message too big. */
    public static final int MESSAGE_TOO_BIG     = 1009;
    /** {@code 1010}, mandatory extension required, used only by clients and never sent by servers. */
    public static final int MANDATORY_EXTENSION = 1010;
    /** {@code 1011}, unexpected internal error. */
    public static final int UNEXPECTED          = 1011;
    /** {@code 1015}, TLS handshake failure, locally synthesized only, never sent on the wire. */
    public static final int TLS_HANDSHAKE       = 1015;

    private WebSocketCode() {
    }
}
