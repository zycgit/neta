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
package net.hasor.neta.codec.quic;

/**
 * Represents a single QUIC stream within a connection.
 * <p>
 * QUIC streams are multiplexed within a connection and can be unidirectional
 * or bidirectional. Stream IDs encode the initiator and directionality:
 * <ul>
 *   <li>Bits 0: 0 = client-initiated, 1 = server-initiated</li>
 *   <li>Bits 1: 0 = bidirectional, 1 = unidirectional</li>
 * </ul>
 * @see QuicStreamState
 */
public class QuicStream {
    private final    long            streamId;
    private volatile QuicStreamState state;
    private          long            sendOffset;
    private          long            receiveOffset;
    private          long            maxSendData;
    private          long            maxReceiveData;
    private          byte[]          accumulatedHeaders;

    /**
     * Creates a new QUIC stream.
     * @param streamId the QUIC stream identifier
     * @param initialMaxData the initial maximum data limit for the stream
     */
    public QuicStream(long streamId, long initialMaxData) {
        this.streamId = streamId;
        this.state = QuicStreamState.IDLE;
        this.sendOffset = 0;
        this.receiveOffset = 0;
        this.maxSendData = initialMaxData;
        this.maxReceiveData = initialMaxData;
    }

    public long streamId() {
        return streamId;
    }

    public QuicStreamState state() {
        return state;
    }

    public void state(QuicStreamState state) {
        this.state = state;
    }

    /** Returns true if this stream was initiated by the client (least significant bit is 0). */
    public boolean isClientInitiated() {
        return (streamId & 0x01) == 0;
    }

    /** Returns true if this stream is bidirectional (bit 1 is 0). */
    public boolean isBidirectional() {
        return (streamId & 0x02) == 0;
    }

    public long sendOffset() {
        return sendOffset;
    }

    public void advanceSendOffset(long bytes) {
        this.sendOffset += bytes;
    }

    public long receiveOffset() {
        return receiveOffset;
    }

    public void advanceReceiveOffset(long bytes) {
        this.receiveOffset += bytes;
    }

    public long maxSendData() {
        return maxSendData;
    }

    public void maxSendData(long value) {
        this.maxSendData = value;
    }

    public long maxReceiveData() {
        return maxReceiveData;
    }

    public void maxReceiveData(long value) {
        this.maxReceiveData = value;
    }

    /** Gets the accumulated header block bytes (used during header reassembly). */
    public byte[] accumulatedHeaders() {
        return accumulatedHeaders;
    }

    /** Sets/appends header block bytes during reassembly. */
    public void appendHeaders(byte[] data) {
        if (this.accumulatedHeaders == null) {
            this.accumulatedHeaders = data;
        } else {
            byte[] merged = new byte[this.accumulatedHeaders.length + data.length];
            System.arraycopy(this.accumulatedHeaders, 0, merged, 0, this.accumulatedHeaders.length);
            System.arraycopy(data, 0, merged, this.accumulatedHeaders.length, data.length);
            this.accumulatedHeaders = merged;
        }
    }

    /** Clears accumulated header bytes after successful decoding. */
    public void clearHeaders() {
        this.accumulatedHeaders = null;
    }

    /** Releases resources held by this stream. */
    public void release() {
        this.accumulatedHeaders = null;
        this.state = QuicStreamState.CLOSED;
    }

    @Override
    public String toString() {
        return "QuicStream{id=" + streamId + ", state=" + state + "}";
    }
}
