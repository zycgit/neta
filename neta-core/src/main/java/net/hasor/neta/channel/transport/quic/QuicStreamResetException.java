/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Exception thrown when a QUIC RESET_STREAM frame sent by the peer is received.
 * <p>Corresponds to RFC 9000 Section 19.4 and indicates that a stream was aborted by the peer together with its final size.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStreamResetException extends QuicException {
    private final long streamId;
    private final long finalSize;

    /**
     * Creates a stream-reset exception.
     * @param errorCode QUIC error code
     * @param streamId stream ID
     * @param finalSize final size declared before reset
     */
    public QuicStreamResetException(long errorCode, long streamId, long finalSize) {
        super(errorCode, "RESET_STREAM: stream=" + streamId + ", errorCode=0x" + Long.toHexString(errorCode) + ", finalSize=" + finalSize);
        this.streamId = streamId;
        this.finalSize = finalSize;
    }

    /**
     * Returns the reset stream ID.
     */
    public long getStreamId() {
        return this.streamId;
    }

    /**
     * Returns the final size declared before the reset.
     */
    public long getFinalSize() {
        return this.finalSize;
    }
}
