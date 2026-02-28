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
package net.hasor.neta.codec.http.h3;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection state container for {@link Http3HttpToFrameEncoder}.
 * <p>
 * Operations are grouped into four categories:
 * <ul>
 *   <li><b>init</b>   — constructor; all state is fully initialized at construction time.</li>
 *   <li><b>append</b> — called by the Encoder to build outbound frames (QPACK encoding,
 *       stream ID allocation).</li>
 *   <li><b>inject</b> — called by the Duplexe to set the current stream ID for server-mode
 *       responses before invoking the Encoder.</li>
 *   <li><b>release</b> — no resources to release (QPACK encoder is GC-eligible).</li>
 * </ul>
 * All fields are private; no caller may access internal sub-objects directly.
 */
class Http3EncoderContent {
    private final QpackEncoder qpackEncoder;
    private final AtomicLong   nextStreamId;
    private       long         currentStreamId;
    private       long         responseStreamId;
    private       boolean      settingsSent;

    Http3EncoderContent(boolean serverMode, int maxTableSize) {
        this.qpackEncoder = new QpackEncoder(maxTableSize, false);
        this.nextStreamId = new AtomicLong(serverMode ? 1 : 0);
        this.currentStreamId = 0;
        this.responseStreamId = -1;
        this.settingsSent = false;
    }

    // ─── preface / settings state ─────────────────────────────────────────────

    /** Returns {@code true} if the initial SETTINGS have been sent. */
    boolean isSettingsSent() {
        return settingsSent;
    }

    /** Marks the initial SETTINGS as sent. */
    void markSettingsSent() {
        this.settingsSent = true;
    }

    // ─── stream ID management ─────────────────────────────────────────────────

    /** Returns the stream ID to use for the current outbound message. */
    long currentStreamId() {
        return currentStreamId;
    }

    /**
     * Sets the stream ID for the next outbound response (server-mode injection by Duplexe).
     * Must be called by the Duplexe before invoking the Encoder on the SND path.
     */
    void setCurrentStreamId(long streamId) {
        this.currentStreamId = streamId;
    }

    /**
     * Allocates and returns the next outbound stream ID for a new request (client mode).
     * Client-initiated bidirectional streams use IDs: 0, 4, 8, ... (increments by 4).
     */
    long allocateNextStreamId() {
        long id = nextStreamId.getAndAdd(4);
        this.currentStreamId = id;
        return id;
    }

    /** Returns the pending response stream ID, or -1 if none. */
    long responseStreamId() {
        return responseStreamId;
    }

    /** Sets the response stream ID (injected by Duplexe from decoder's queue). */
    void setResponseStreamId(long streamId) {
        this.responseStreamId = streamId;
    }

    /**
     * Consumes and returns the pending response stream ID.
     * After consumption, the internal value resets to -1.
     * @return the response stream ID, or -1 if none was pending
     */
    long consumeResponseStreamId() {
        long id = this.responseStreamId;
        if (id >= 0) {
            this.responseStreamId = -1;
            this.currentStreamId = id;
        }
        return id;
    }

    // ─── QPACK header encoding ────────────────────────────────────────────────

    /** Begins a new QPACK header-block encoding session. */
    void beginHeaderEncode() {
        qpackEncoder.beginEncode();
    }

    /** Encodes a single header field into the current session. */
    void encodeHeader(String name, String value) {
        qpackEncoder.encodeHeaderDirect(name, value);
    }

    /**
     * Returns the number of bytes encoded so far in the current session.
     */
    int headerEncodedLength() {
        return qpackEncoder.encodedLength();
    }

    /**
     * Returns a reference to the internal encode buffer.
     * Valid from index 0 to {@link #headerEncodedLength()} - 1.
     */
    byte[] headerEncodedBuffer() {
        return qpackEncoder.encodedBuffer();
    }

    /**
     * Finalises encoding and returns a copy of the complete QPACK-compressed header block.
     * Must be called after {@link #beginHeaderEncode()} and all {@link #encodeHeader} calls.
     */
    byte[] finishHeaderEncode() {
        int len = qpackEncoder.encodedLength();
        byte[] block = new byte[len];
        System.arraycopy(qpackEncoder.encodedBuffer(), 0, block, 0, len);
        return block;
    }
}
