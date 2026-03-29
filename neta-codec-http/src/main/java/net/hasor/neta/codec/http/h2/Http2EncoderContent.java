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
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.neta.codec.http.HttpResponse;

/**
 * Per-connection state container for {@link Http2ObjectEncoder}.
 * <p>
 * Operations are grouped into four categories:
 * <ul>
 *   <li><b>init</b>   — constructor; all state is fully initialized at construction time.</li>
 *   <li><b>append</b> — called by the Encoder to build outbound frames (HPACK encoding,
 *       stream ID allocation, preface tracking).</li>
 *   <li><b>bind</b>   — current stream binding is refreshed from explicit outbound message
 *       stream IDs or higher-layer response association.</li>
 *   <li><b>release</b> — no resources to release (HPACK encoder is GC-eligible).</li>
 * </ul>
 * All fields are private; no caller may access internal sub-objects directly.
 */
class Http2EncoderContent {
    private final HpackEncoder      hpackEncoder;
    private final Http2Settings     localSettings;
    private final AtomicInteger     nextStreamId;
    private final Queue<Http2Frame> pendingOutboundFrames;
    private       boolean           prefaceSent;
    private       int               currentStreamId = 0;
    private       HttpRequest       pendingRequest;
    private       HttpResponse      pendingResponse;
    private       boolean           trailingHeadersSent;

    Http2EncoderContent(boolean serverMode, Http2Settings localSettings) {
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : new Http2Settings();
        this.hpackEncoder = new HpackEncoder((int) this.localSettings.headerTableSize());
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
        this.pendingOutboundFrames = new LinkedList<>();
        this.prefaceSent = false;
    }

    // ─── preface state ────────────────────────────────────────────────────────

    /** Returns {@code true} if the connection preface has already been sent. */
    boolean isPrefaceSent() {
        return prefaceSent;
    }

    /** Marks the connection preface as sent. */
    void markPrefaceSent() {
        this.prefaceSent = true;
    }

    // ─── stream ID management ─────────────────────────────────────────────────

    /** Returns the stream ID to use for the current outbound message. */
    int currentStreamId() {
        return currentStreamId;
    }

    /** Sets the stream ID to be reused by the next outbound message fragment sequence. */
    void setCurrentStreamId(int streamId) {
        this.currentStreamId = streamId;
    }

    HttpRequest pendingRequest() {
        return this.pendingRequest;
    }

    void pendingRequest(HttpRequest pendingRequest) {
        this.pendingRequest = pendingRequest;
    }

    HttpResponse pendingResponse() {
        return this.pendingResponse;
    }

    void pendingResponse(HttpResponse pendingResponse) {
        this.pendingResponse = pendingResponse;
    }

    void clearPendingStartLine() {
        this.pendingRequest = null;
        this.pendingResponse = null;
    }

    boolean trailingHeadersSent() {
        return this.trailingHeadersSent;
    }

    void trailingHeadersSent(boolean trailingHeadersSent) {
        this.trailingHeadersSent = trailingHeadersSent;
    }

    /**
     * Allocates and returns the next outbound stream ID for a new request (client mode).
     * Uses odd-numbered IDs and increments by 2 per RFC 9113.
     */
    int allocateNextStreamId() {
        int id = nextStreamId.getAndAdd(2);
        this.currentStreamId = id;
        return id;
    }

    void queueOutboundFrame(Http2Frame frame) {
        if (frame != null) {
            this.pendingOutboundFrames.offer(frame);
        }
    }

    void queueOutboundFrames(Iterable<Http2Frame> frames) {
        if (frames == null) {
            return;
        }
        for (Http2Frame frame : frames) {
            this.queueOutboundFrame(frame);
        }
    }

    Http2Frame pollPendingOutboundFrame() {
        return this.pendingOutboundFrames.poll();
    }

    boolean hasPendingOutboundFrames() {
        return !this.pendingOutboundFrames.isEmpty();
    }

    // ─── HPACK header encoding ────────────────────────────────────────────────

    /** Begins a new HPACK header-block encoding session. */
    void beginHeaderEncode() {
        hpackEncoder.beginEncode();
    }

    /** Encodes a single header field into the current session. */
    void encodeHeader(String name, String value) {
        hpackEncoder.encodeHeaderDirect(name, value);
    }

    /**
     * Finalises encoding and returns the complete HPACK-compressed header block.
     * Must be called after {@link #beginHeaderEncode()} and all {@link #encodeHeader} calls.
     */
    byte[] finishHeaderEncode() {
        int len = hpackEncoder.encodedLength();
        byte[] block = new byte[len];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, block, 0, len);
        return block;
    }
}
