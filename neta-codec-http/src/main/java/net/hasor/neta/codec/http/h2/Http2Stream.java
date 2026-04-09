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
 * Represents the accumulated state of an HTTP/2 stream.
 * <p>
 * A single HTTP/2 connection can contain multiple concurrent streams, each identified by a unique stream ID.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class Http2Stream {
    private final long             streamId;
    private       Http2StreamState state;
    private       ByteBuf          accumulatedHeaderBlock;
    private       boolean          endStreamPending;
    private       boolean          initialHeadersEmitted;
    private       boolean          terminalObjectEmitted;

    /**
     * Creates a new HTTP/2 stream.
     * @param streamId the stream identifier; client-initiated streams are odd and server-initiated streams are even
     */
    public Http2Stream(long streamId) {
        this.streamId = streamId;
        this.state = Http2StreamState.IDLE;
    }

    /**
     * Returns the stream ID.
     */
    public long streamId() {
        return streamId;
    }

    /**
     * Returns the current stream state.
     */
    public Http2StreamState state() {
        return state;
    }

    /**
     * Sets the current stream state.
     * @param state the new state
     */
    public void state(Http2StreamState state) {
        this.state = state;
    }

    /**
     * Returns the accumulated header block.
     */
    public ByteBuf accumulatedHeaderBlock() {
        return accumulatedHeaderBlock;
    }

    /**
     * Sets the accumulated header block.
     * @param buf the new header-block buffer
     */
    public void accumulatedHeaderBlock(ByteBuf buf) {
        this.accumulatedHeaderBlock = buf;
    }

    /**
     * Returns {@code true} if the original HEADERS frame carried END_STREAM but not END_HEADERS yet.
     */
    public boolean isEndStreamPending() {
        return endStreamPending;
    }

    /**
     * Records whether the peer intends to close the stream after the full header block arrives.
     * @param pending whether closure is pending
     */
    public void setEndStreamPending(boolean pending) {
        this.endStreamPending = pending;
    }

    /**
     * Returns whether the initial headers have already been emitted.
     */
    public boolean isInitialHeadersEmitted() {
        return this.initialHeadersEmitted;
    }

    /**
     * Sets whether the initial headers have already been emitted.
     * @param initialHeadersEmitted whether they have been emitted
     */
    public void setInitialHeadersEmitted(boolean initialHeadersEmitted) {
        this.initialHeadersEmitted = initialHeadersEmitted;
    }

    /**
     * Returns whether the terminal object has already been emitted.
     */
    public boolean isTerminalObjectEmitted() {
        return this.terminalObjectEmitted;
    }

    /**
     * Sets whether the terminal object has already been emitted.
     * @param terminalObjectEmitted whether it has been emitted
     */
    public void setTerminalObjectEmitted(boolean terminalObjectEmitted) {
        this.terminalObjectEmitted = terminalObjectEmitted;
    }

    /**
     * Releases resources held by the current stream.
     */
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
