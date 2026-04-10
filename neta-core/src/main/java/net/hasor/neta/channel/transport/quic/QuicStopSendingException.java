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
