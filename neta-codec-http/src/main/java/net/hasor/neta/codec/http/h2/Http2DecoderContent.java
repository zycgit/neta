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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * Per-connection state container shared between {@link Http2FrameToHttpDecoder}
 * (writer / append path) and {@link net.hasor.neta.codec.http.h2.Http2ServerDuplexe}
 * (reader / poll path).
 * <p>
 * Operations are grouped into four categories:
 * <ul>
 *   <li><b>init</b>  — constructor + one-time configuration injected by the Duplexe.</li>
 *   <li><b>append</b> — called exclusively by {@link Http2FrameToHttpDecoder} to populate
 *       state as frames arrive.</li>
 *   <li><b>poll</b>   — called exclusively by the Duplexe on the SND cycle to drain
 *       queued control frames.</li>
 *   <li><b>release</b> — called on connection close to free resources.</li>
 * </ul>
 * All fields are private; no caller may access internal collections or sub-objects directly.
 */
class Http2DecoderContent {
    private final Queue<Integer>            responseStreamIdQueue   = new LinkedList<>();
    private final Queue<byte[]>             pendingPingAcks         = new LinkedList<>();
    private final Queue<Http2Frame>         pendingWindowUpdates    = new LinkedList<>();
    private final Map<Integer, Http2Stream> streams                 = new HashMap<>();
    private final HpackDecoder              hpackDecoder;
    private final Http2Settings             remoteSettings          = new Http2Settings();
    private       boolean                   prefaceReceived;
    private       boolean                   pendingSettingsAck;
    private       int                       serverInitialWindowSize = 65535;
    private       int                       lastEmittedStreamId     = 0;

    Http2DecoderContent(boolean serverMode, int maxHeaderTableSize, int maxHeaderListSize) {
        this.hpackDecoder = new HpackDecoder(maxHeaderTableSize, maxHeaderListSize);
        this.prefaceReceived = !serverMode;
    }

    // ─── init / config ──────────────────────────────────────────────────────────

    /** Sets the server's desired initial flow control window size (injected by Duplexe on init). */
    void setServerInitialWindowSize(int size) {
        this.serverInitialWindowSize = size;
    }

    // ─── append (called by Http2FrameToHttpDecoder) ───────────────────────────────

    /** Marks the connection preface as received. */
    void markPrefaceReceived() {
        this.prefaceReceived = true;
    }

    /** Returns or creates the stream for the given stream ID. */
    Http2Stream getOrCreateStream(int streamId) {
        return streams.computeIfAbsent(streamId, id -> new Http2Stream(id, remoteSettings.initialWindowSize()));
    }

    /** Returns the stream for the given stream ID, or {@code null} if absent. */
    Http2Stream getStream(int streamId) {
        return streams.get(streamId);
    }

    /** Closes and releases the stream for the given stream ID. */
    void closeStream(int streamId) {
        Http2Stream stream = streams.remove(streamId);
        if (stream != null) {
            stream.state(Http2StreamState.CLOSED);
            stream.release();
        }
    }

    /** Removes any pending response-stream-ID entry for the given stream from the FIFO queue. */
    void removeFromResponseQueue(int streamId) {
        responseStreamIdQueue.removeIf(id -> id == streamId);
    }

    /** Adjusts the send window of the given stream by {@code increment} bytes. */
    void adjustStreamSendWindow(int streamId, int increment) {
        Http2Stream stream = streams.get(streamId);
        if (stream != null) {
            stream.adjustSendWindowSize(increment);
        }
    }

    /** Decodes an HPACK-compressed header block. */
    HttpHeaders decodeHeaders(byte[] data, int offset, int length) {
        return hpackDecoder.decode(data, offset, length);
    }

    /** Queues a PING ACK payload to be sent to the remote peer. */
    void offerPingAck(byte[] payload) {
        pendingPingAcks.offer(payload);
    }

    /** Queues a WINDOW_UPDATE frame to be sent to the remote peer. */
    void offerWindowUpdate(Http2Frame frame) {
        pendingWindowUpdates.offer(frame);
    }

    /** Records a completed request stream ID for later response association. */
    void offerResponseStreamId(int streamId) {
        responseStreamIdQueue.offer(streamId);
    }

    /** Marks that a SETTINGS ACK must be sent on the next SND cycle. */
    void markSettingsAckPending() {
        this.pendingSettingsAck = true;
    }

    /**
     * Applies a remote SETTINGS parameter and updates the HPACK decoder table size
     * atomically if {@code SETTINGS_HEADER_TABLE_SIZE} was included.
     */
    void applyRemoteSetting(int id, long value) {
        remoteSettings.applySetting(id, value);
        hpackDecoder.setMaxHeaderTableSize((int) remoteSettings.headerTableSize());
    }

    /** Records the stream ID of the most recently emitted {@link net.hasor.neta.codec.http.HttpObject}. */
    void setLastEmittedStreamId(int streamId) {
        this.lastEmittedStreamId = streamId;
    }

    /** Returns the server's configured initial flow control window size. */
    int serverInitialWindowSize() {
        return serverInitialWindowSize;
    }

    /** Returns the remote peer's negotiated initial flow control window size. */
    int remoteInitialWindowSize() {
        return remoteSettings.initialWindowSize();
    }

    // ─── poll (called by Http2ServerDuplexe on SND cycle) ────────────────────────

    /** Polls the next response stream ID. Returns -1 if the queue is empty. */
    int pollResponseStreamId() {
        Integer id = this.responseStreamIdQueue.poll();
        return id != null ? id : -1;
    }

    /** Polls the next pending PING ACK payload, or {@code null} if none pending. */
    byte[] pollPendingPingAck() {
        return this.pendingPingAcks.poll();
    }

    /** Polls the next pending WINDOW_UPDATE frame, or {@code null} if none pending. */
    Http2Frame pollPendingWindowUpdate() {
        return this.pendingWindowUpdates.poll();
    }

    /** Checks and atomically consumes the pending SETTINGS ACK flag. */
    boolean consumeSettingsAck() {
        if (this.pendingSettingsAck) {
            this.pendingSettingsAck = false;
            return true;
        }
        return false;
    }

    // ─── state view (read-only, for Http2ContextImpl) ────────────────────────────

    /** Returns {@code true} if the connection preface has been received. */
    boolean isPrefaceReceived() {
        return prefaceReceived;
    }

    /** Returns the highest stream ID currently tracked. */
    int lastStreamId() {
        int max = 0;
        for (Integer id : this.streams.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    /** Returns the stream ID of the most recently emitted HttpObject. */
    int lastEmittedStreamId() {
        return lastEmittedStreamId;
    }

    /** Returns the remote peer's negotiated {@code SETTINGS_MAX_CONCURRENT_STREAMS}. */
    long remoteMaxConcurrentStreams() {
        return remoteSettings.maxConcurrentStreams();
    }

    /** Returns the remote peer's negotiated {@code SETTINGS_MAX_FRAME_SIZE}. */
    int remoteMaxFrameSize() {
        return remoteSettings.maxFrameSize();
    }

    // ─── release ───────────────────────────────────────────────────────────────────

    /** Releases all stream resources on connection close. */
    void releaseAll() {
        for (Http2Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }
}
