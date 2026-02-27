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
 * Thrown when a QUIC {@code STOP_SENDING} frame is received from the peer (RFC 9000 §19.5).
 * <p>
 * The peer is requesting that the local side stop sending data on this stream.
 * Per RFC 9000 §3.5, the application should normally respond by sending a
 * {@code RESET_STREAM} frame. This exception is propagated through the stream's
 * pipeline as a <em>send</em> error (since it affects the sending direction).
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicStopSendingException extends QuicException {
    private final long streamId;

    /**
     * @param errorCode the application error code from the STOP_SENDING frame
     * @param streamId the stream ID on which STOP_SENDING was received
     */
    public QuicStopSendingException(long errorCode, long streamId) {
        super(errorCode, "STOP_SENDING: stream=" + streamId + ", errorCode=0x" + Long.toHexString(errorCode));
        this.streamId = streamId;
    }

    /** Returns the stream ID on which STOP_SENDING was received. */
    public long getStreamId() {
        return this.streamId;
    }
}
