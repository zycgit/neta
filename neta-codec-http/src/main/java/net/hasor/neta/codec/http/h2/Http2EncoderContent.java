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
 * Connection-level state container used by {@link Http2ObjectEncoder}.
 * <p>
 * Operations are divided into four categories:
 * <ul>
 *   <li><b>init</b>: the constructor, where all state is initialized.</li>
 *   <li><b>append</b>: called by the encoder to build outbound frames, including HPACK encoding, stream-ID allocation, and preface tracking.</li>
 *   <li><b>bind</b>: refreshes the current stream binding according to an explicit outbound message stream ID or higher-level response association.</li>
 *   <li><b>release</b>: no extra resources need explicit release; the HPACK encoder can be reclaimed by the GC.</li>
 * </ul>
 * All fields are private, and callers must not access internal child objects directly.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-28
 */
class Http2EncoderContent {
    private final HpackEncoder      hpackEncoder;
    private final Http2Settings     localSettings;
    private final AtomicInteger     nextStreamId;
    private final Queue<Http2Frame> pendingOutboundFrames;
    private       boolean           prefaceSent;
    private       long              currentStreamId = 0;
    private       HttpRequest       pendingRequest;
    private       HttpResponse      pendingResponse;
    private       boolean           trailingHeadersSent;

    /**
     * Creates the encoder-side connection state container.
     * @param serverMode whether the current endpoint runs in server mode; this determines the initial stream-ID parity
     * @param localSettings the HTTP/2 settings used during encoding; if {@code null}, an empty default configuration is used
     */
    public Http2EncoderContent(boolean serverMode, Http2Settings localSettings) {
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : new Http2Settings();
        this.hpackEncoder = new HpackEncoder((int) this.localSettings.headerTableSize());
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
        this.pendingOutboundFrames = new LinkedList<>();
        this.prefaceSent = false;
    }

    // ─── preface state ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} once the connection preface has been sent.
     */
    public boolean isPrefaceSent() {
        return prefaceSent;
    }

    /**
     * Marks the connection preface as sent.
     */
    public void markPrefaceSent() {
        this.prefaceSent = true;
    }

    // ─── stream ID management ─────────────────────────────────────────────────

    /**
     * Returns the stream ID that should be used for the current outbound message.
     */
    public long currentStreamId() {
        return currentStreamId;
    }

    /**
     * Sets the stream ID that the next outbound message fragment sequence should reuse.
     */
    public void setCurrentStreamId(long streamId) {
        this.currentStreamId = streamId;
    }

    /**
     * Returns the currently buffered request start-line object that has not yet entered header encoding.
     */
    public HttpRequest pendingRequest() {
        return this.pendingRequest;
    }

    /**
     * Stores the request start-line object that is about to be encoded.
     */
    public void pendingRequest(HttpRequest pendingRequest) {
        this.pendingRequest = pendingRequest;
    }

    /**
     * Returns the currently buffered response start-line object that has not yet entered header encoding.
     */
    public HttpResponse pendingResponse() {
        return this.pendingResponse;
    }

    /**
     * Stores the response start-line object that is about to be encoded.
     */
    public void pendingResponse(HttpResponse pendingResponse) {
        this.pendingResponse = pendingResponse;
    }

    /**
     * Clears the currently buffered request/response start-line binding.
     */
    public void clearPendingStartLine() {
        this.pendingRequest = null;
        this.pendingResponse = null;
    }

    /**
     * Returns {@code true} once trailing headers have been sent on the current stream.
     */
    public boolean trailingHeadersSent() {
        return this.trailingHeadersSent;
    }

    /**
     * Marks whether trailing headers have already been sent on the current stream.
     */
    public void trailingHeadersSent(boolean trailingHeadersSent) {
        this.trailingHeadersSent = trailingHeadersSent;
    }

    /**
     * Allocates and returns the next stream ID for a new outbound request, used only in client mode.
     * According to RFC 9113, odd IDs are used here and incremented by 2.
     */
    public int allocateNextStreamId() {
        int id = nextStreamId.getAndAdd(2);
        this.currentStreamId = id;
        return id;
    }

    /**
     * Adds a frame to the outbound staging queue.
     */
    public void queueOutboundFrame(Http2Frame frame) {
        if (frame != null) {
            this.pendingOutboundFrames.offer(frame);
        }
    }

    /**
     * Appends a batch of frames to the outbound staging queue.
     */
    public void queueOutboundFrames(Iterable<Http2Frame> frames) {
        if (frames == null) {
            return;
        }
        for (Http2Frame frame : frames) {
            this.queueOutboundFrame(frame);
        }
    }

    /**
     * Polls one pending outbound frame, or {@code null} if the queue is empty.
     */
    public Http2Frame pollPendingOutboundFrame() {
        return this.pendingOutboundFrames.poll();
    }

    /**
     * Returns {@code true} when the outbound staging queue still contains frames to send.
     */
    public boolean hasPendingOutboundFrames() {
        return !this.pendingOutboundFrames.isEmpty();
    }

    // ─── HPACK header encoding ────────────────────────────────────────────────

    /**
     * Starts a new HPACK header-block encoding session.
     */
    public void beginHeaderEncode() {
        hpackEncoder.beginEncode();
    }

    /**
     * Encodes a single header field in the current session.
     */
    public void encodeHeader(String name, String value) {
        hpackEncoder.encodeHeaderDirect(name, value);
    }

    /**
     * Finishes encoding and returns the complete HPACK-compressed header block.
     * This must be executed after {@link #beginHeaderEncode()} and after all {@link #encodeHeader} calls.
     */
    public byte[] finishHeaderEncode() {
        int len = hpackEncoder.encodedLength();
        byte[] block = new byte[len];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, block, 0, len);
        return block;
    }
}
