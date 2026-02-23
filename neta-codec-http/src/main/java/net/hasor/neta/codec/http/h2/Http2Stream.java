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
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * Represents an HTTP/2 stream's accumulated state.
 * <p>
 * Each HTTP/2 connection can have multiple concurrent streams,
 * each identified by a unique stream ID.
 */
class Http2Stream {
    private final int              streamId;
    private       Http2StreamState state;
    private       HttpHeaders      accumulatedHeaders;
    private       ByteBuf          accumulatedHeaderBlock;
    private       int              sendWindowSize;
    private       int              recvWindowSize;

    /**
     * Creates a new HTTP/2 stream.
     * @param streamId the stream identifier (odd for client-initiated, even for server-initiated)
     * @param initialWindowSize initial flow-control window size
     */
    public Http2Stream(int streamId, int initialWindowSize) {
        this.streamId = streamId;
        this.state = Http2StreamState.IDLE;
        this.sendWindowSize = initialWindowSize;
        this.recvWindowSize = initialWindowSize;
    }

    public int streamId() {
        return streamId;
    }

    public Http2StreamState state() {
        return state;
    }

    public void state(Http2StreamState state) {
        this.state = state;
    }

    public HttpHeaders accumulatedHeaders() {
        return accumulatedHeaders;
    }

    public void accumulatedHeaders(HttpHeaders headers) {
        this.accumulatedHeaders = headers;
    }

    public ByteBuf accumulatedHeaderBlock() {
        return accumulatedHeaderBlock;
    }

    public void accumulatedHeaderBlock(ByteBuf buf) {
        this.accumulatedHeaderBlock = buf;
    }

    public int sendWindowSize() {
        return sendWindowSize;
    }

    public void adjustSendWindowSize(int delta) {
        this.sendWindowSize += delta;
    }

    public int recvWindowSize() {
        return recvWindowSize;
    }

    public void adjustRecvWindowSize(int delta) {
        this.recvWindowSize += delta;
    }

    /** Releases resources held by this stream. */
    public void release() {
        if (accumulatedHeaderBlock != null) {
            accumulatedHeaderBlock.free();
            accumulatedHeaderBlock = null;
        }
        accumulatedHeaders = null;
    }

    @Override
    public String toString() {
        return "Http2Stream{id=" + streamId + ", state=" + state + "}";
    }
}
