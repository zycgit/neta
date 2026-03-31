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

/**
 * Represents an HTTP/2 stream's accumulated state.
 * <p>
 * Each HTTP/2 connection can have multiple concurrent streams,
 * each identified by a unique stream ID.
 */
class Http2Stream {
    private final int              streamId;
    private       Http2StreamState state;
    private       ByteBuf          accumulatedHeaderBlock;
    private       boolean          endStreamPending;
    private       boolean          initialHeadersEmitted;
    private       boolean          terminalObjectEmitted;

    /**
     * Creates a new HTTP/2 stream.
     * @param streamId the stream identifier (odd for client-initiated, even for server-initiated)
     */
    public Http2Stream(int streamId) {
        this.streamId = streamId;
        this.state = Http2StreamState.IDLE;
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

    public ByteBuf accumulatedHeaderBlock() {
        return accumulatedHeaderBlock;
    }

    public void accumulatedHeaderBlock(ByteBuf buf) {
        this.accumulatedHeaderBlock = buf;
    }

    /** Returns true if the original HEADERS frame carried END_STREAM but not END_HEADERS. */
    public boolean isEndStreamPending() {
        return endStreamPending;
    }

    /** Records whether the peer intends to close the stream after the full header block arrives. */
    public void setEndStreamPending(boolean pending) {
        this.endStreamPending = pending;
    }

    public boolean isInitialHeadersEmitted() {
        return this.initialHeadersEmitted;
    }

    public void setInitialHeadersEmitted(boolean initialHeadersEmitted) {
        this.initialHeadersEmitted = initialHeadersEmitted;
    }

    public boolean isTerminalObjectEmitted() {
        return this.terminalObjectEmitted;
    }

    public void setTerminalObjectEmitted(boolean terminalObjectEmitted) {
        this.terminalObjectEmitted = terminalObjectEmitted;
    }

    /** Releases resources held by this stream. */
    public void release() {
        if (accumulatedHeaderBlock != null) {
            accumulatedHeaderBlock.free();
            accumulatedHeaderBlock = null;
        }
        this.endStreamPending = false;
        this.initialHeadersEmitted = false;
        this.terminalObjectEmitted = false;
    }

    @Override
    public String toString() {
        return "Http2Stream{id=" + streamId + ", state=" + state + "}";
    }
}
