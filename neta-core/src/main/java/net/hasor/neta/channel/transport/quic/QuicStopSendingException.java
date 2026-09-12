/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Exception thrown when a QUIC STOP_SENDING frame sent by the peer is received.
 * <p>Corresponds to RFC 9000 Section 19.5 and notifies the application layer that a stream should stop sending further data.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStopSendingException extends QuicException {
    private final long streamId;

    /**
     * Creates a STOP_SENDING exception.
     * @param errorCode application error code
     * @param streamId target stream ID
     */
    public QuicStopSendingException(long errorCode, long streamId) {
        super(errorCode, "STOP_SENDING: stream=" + streamId + ", errorCode=0x" + Long.toHexString(errorCode));
        this.streamId = streamId;
    }

    /**
     * Returns the stream ID for which STOP_SENDING was received.
     */
    public long getStreamId() {
        return this.streamId;
    }
}
