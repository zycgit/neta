/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.neta.channel.SoException;
/**
 * Base class for QUIC protocol exceptions, carrying a QUIC error code.
 * <p>Corresponds to RFC 9000 Section 20 and serves as a unified exception entry point for connection-level or stream-level protocol errors.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicException extends SoException {
    private final long errorCode;

    /**
     * Creates an exception from an error code and message.
     * @param errorCode QUIC error code
     * @param message exception message
     */
    public QuicException(long errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates an exception from an error code, message, and root cause.
     * @param errorCode QUIC error code
     * @param message exception message
     * @param cause root cause exception
     */
    public QuicException(long errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the associated QUIC error code.
     */
    public long getErrorCode() {
        return this.errorCode;
    }
}
