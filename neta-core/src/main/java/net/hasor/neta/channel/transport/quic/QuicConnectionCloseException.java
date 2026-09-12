/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Exception thrown when a QUIC CONNECTION_CLOSE frame is received or sent.
 * <p>Corresponds to RFC 9000 Section 19.19 and represents connection-level shutdown together with its error reason.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicConnectionCloseException extends QuicException {
    private final String reason;

    /**
     * Creates a connection-close exception from an error code and reason.
     * @param errorCode QUIC error code
     * @param reason close reason
     */
    public QuicConnectionCloseException(long errorCode, String reason) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason);
        this.reason = reason != null ? reason : "";
    }

    /**
     * Creates a connection-close exception from an error code, reason, and root cause.
     * @param errorCode QUIC error code
     * @param reason close reason
     * @param cause root cause exception
     */
    public QuicConnectionCloseException(long errorCode, String reason, Throwable cause) {
        super(errorCode, "CONNECTION_CLOSE: errorCode=0x" + Long.toHexString(errorCode) + ", reason=" + reason, cause);
        this.reason = reason != null ? reason : "";
    }

    /**
     * Returns the reason text carried in CONNECTION_CLOSE.
     */
    public String getReason() {
        return this.reason;
    }
}
