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
package net.hasor.neta.codec.http3;

/**
 * Represents a single HTTP/3 stream within a connection.
 * <p>
 * HTTP/3 uses QUIC streams for multiplexing. Each request/response pair
 * uses a separate bidirectional QUIC stream. This class tracks the
 * HTTP-layer state and accumulated header block for each stream.
 * @see Http3StreamState
 */
public class Http3Stream {
    private final    long             streamId;
    private volatile Http3StreamState state;
    private          byte[]           accumulatedHeaderBlock;
    private          boolean          headersReceived;
    private          boolean          trailersReceived;

    /**
     * Creates a new HTTP/3 stream.
     * @param streamId the QUIC stream identifier
     */
    public Http3Stream(long streamId) {
        this.streamId = streamId;
        this.state = Http3StreamState.IDLE;
        this.headersReceived = false;
        this.trailersReceived = false;
    }

    public long streamId() {
        return streamId;
    }

    public Http3StreamState state() {
        return state;
    }

    public void state(Http3StreamState state) {
        this.state = state;
    }

    public boolean headersReceived() {
        return headersReceived;
    }

    public void markHeadersReceived() {
        this.headersReceived = true;
    }

    public boolean trailersReceived() {
        return trailersReceived;
    }

    public void markTrailersReceived() {
        this.trailersReceived = true;
    }

    /** Gets the accumulated header block bytes (for multi-frame header reassembly). */
    public byte[] accumulatedHeaderBlock() {
        return accumulatedHeaderBlock;
    }

    /** Appends header block data. */
    public void appendHeaderBlock(byte[] data) {
        if (this.accumulatedHeaderBlock == null) {
            this.accumulatedHeaderBlock = data;
        } else {
            byte[] merged = new byte[this.accumulatedHeaderBlock.length + data.length];
            System.arraycopy(this.accumulatedHeaderBlock, 0, merged, 0, this.accumulatedHeaderBlock.length);
            System.arraycopy(data, 0, merged, this.accumulatedHeaderBlock.length, data.length);
            this.accumulatedHeaderBlock = merged;
        }
    }

    /** Clears the accumulated header block. */
    public void clearHeaderBlock() {
        this.accumulatedHeaderBlock = null;
    }

    /** Releases resources held by this stream. */
    public void release() {
        this.accumulatedHeaderBlock = null;
        this.state = Http3StreamState.CLOSED;
    }

    @Override
    public String toString() {
        return "Http3Stream{id=" + streamId + ", state=" + state + "}";
    }
}
