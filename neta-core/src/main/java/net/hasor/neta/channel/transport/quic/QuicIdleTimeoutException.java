/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.neta.channel.SoTimeoutException;
/**
 * Exception thrown when a QUIC connection or stream is closed due to idle timeout.
 * <p>Corresponds to RFC 9000 Section 10.1 and can be used to distinguish connection-level and stream-level timeout closures.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicIdleTimeoutException extends SoTimeoutException {
    private final boolean connectionLevel;

    /**
     * Creates an idle-timeout exception.
     * @param message exception message
     * @param connectionLevel whether this is a connection-level timeout; false indicates a stream-level timeout
     */
    public QuicIdleTimeoutException(String message, boolean connectionLevel) {
        super(message);
        this.connectionLevel = connectionLevel;
    }

    /**
     * Returns whether this is a connection-level timeout.
     * @return true for a connection-level timeout, false for a stream-level timeout
     */
    public boolean isConnectionLevel() {
        return this.connectionLevel;
    }
}
