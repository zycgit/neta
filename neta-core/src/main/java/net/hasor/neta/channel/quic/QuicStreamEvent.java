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
 * User event fired on a {@link QuicChannel} when a QUIC stream is opened or closed.
 * <p>
 * Similar to {@link net.hasor.neta.codec.ssl.SslEvent} for SSL handshake events,
 * this event allows pipeline handlers to react to stream lifecycle changes
 * via the {@code onUserEvent} callback.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel
 */
public class QuicStreamEvent {
    private final long    streamId;
    private final boolean opened;

    /**
     * Creates a new stream event.
     * @param streamId the QUIC stream identifier (RFC 9000 §2.1)
     * @param opened true if the stream was just opened, false if closed
     */
    public QuicStreamEvent(long streamId, boolean opened) {
        this.streamId = streamId;
        this.opened = opened;
    }

    /** Returns the QUIC stream identifier. */
    public long getStreamId() {
        return this.streamId;
    }

    /** Returns true if the stream was opened. */
    public boolean isOpened() {
        return this.opened;
    }

    /** Returns true if the stream was closed. */
    public boolean isClosed() {
        return !this.opened;
    }

    /** Returns true if this is a unidirectional stream. */
    public boolean isUnidirectional() {
        return (this.streamId & 0x02) != 0;
    }

    /** Returns true if this stream was initiated by the client. */
    public boolean isClientInitiated() {
        return (this.streamId & 0x01) == 0;
    }

    @Override
    public String toString() {
        return "QuicStreamEvent{streamId=" + this.streamId + ", " + (this.opened ? "OPENED" : "CLOSED") + "}";
    }
}
