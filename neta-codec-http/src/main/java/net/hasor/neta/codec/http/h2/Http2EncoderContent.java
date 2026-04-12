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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.codec.http.HttpObject;
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
    private final HpackEncoder  hpackEncoder;
    private final Http2Settings localSettings;
    private final AtomicInteger nextStreamId;
    private final Set<Long>     pendingUpgradeStreams;
    private       boolean       prefaceSent;
    private       long          currentStreamId = 0;
    private       HttpObject    pendingStartLine;
    private       boolean       trailingHeadersSent;

    /**
     * Creates the encoder-side connection state container.
     * @param serverMode whether the current endpoint runs in server mode; this determines the initial stream-ID parity
     * @param localSettings the HTTP/2 settings used during encoding; if {@code null}, an empty default configuration is used
     */
    public Http2EncoderContent(boolean serverMode, Http2Settings localSettings) {
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : new Http2Settings();
        this.hpackEncoder = new HpackEncoder((int) this.localSettings.headerTableSize());
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
        this.pendingUpgradeStreams = new HashSet<>();
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
    public HttpObject pendingStartLine() {
        return this.pendingStartLine;
    }

    /**
     * Stores the request/response start-line object that is about to be encoded.
     */
    public void pendingStartLine(HttpObject pendingStartLine) {
        if (pendingStartLine != null && !(pendingStartLine instanceof HttpRequest) && !(pendingStartLine instanceof HttpResponse)) {
            throw new IllegalArgumentException("pendingStartLine must be HttpRequest or HttpResponse");
        }
        this.pendingStartLine = pendingStartLine;
    }

    public boolean pendingStartLineIsRequest() {
        return this.pendingStartLine instanceof HttpRequest;
    }

    public boolean pendingStartLineIsResponse() {
        return this.pendingStartLine instanceof HttpResponse;
    }

    public HttpRequest pendingRequest() {
        return this.pendingStartLineIsRequest() ? (HttpRequest) this.pendingStartLine : null;
    }

    public HttpResponse pendingResponse() {
        return this.pendingStartLineIsResponse() ? (HttpResponse) this.pendingStartLine : null;
    }

    /**
     * Clears the currently buffered request/response start-line binding.
     */
    public void clearPendingStartLine() {
        this.pendingStartLine = null;
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

    public void markPendingUpgradeStream(long streamId) {
        if (streamId > 0) {
            this.pendingUpgradeStreams.add(streamId);
        }
    }

    public boolean consumePendingUpgradeStream(long streamId) {
        return streamId > 0 && this.pendingUpgradeStreams.remove(streamId);
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
