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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * Per-connection state container shared between {@link Http3FrameToHttpDecoder}
 * (writer / append path) and {@link Http3ServerDuplexe} (reader / poll path).
 * <p>
 * Operations are grouped into four categories:
 * <ul>
 *   <li><b>init</b>  — constructor + one-time configuration injected by the Duplexe.</li>
 *   <li><b>append</b> — called exclusively by {@link Http3FrameToHttpDecoder} to populate
 *       state as frames arrive.</li>
 *   <li><b>poll</b>   — called exclusively by the Duplexe on the SND cycle to drain
 *       queued control data.</li>
 *   <li><b>release</b> — called on connection close to free resources.</li>
 * </ul>
 * All fields are private; no caller may access internal collections or sub-objects directly.
 */
class Http3DecoderContent {
    private final QpackDecoder           qpackDecoder;
    private final Map<Long, Http3Stream> streams               = new HashMap<>();
    private final Http3Settings          remoteSettings        = new Http3Settings();
    private final Queue<Long>            responseStreamIdQueue = new LinkedList<>();
    private       boolean                settingsReceived;

    Http3DecoderContent(int maxTableSize, int maxHeaderListSize) {
        this.qpackDecoder = new QpackDecoder(maxTableSize, maxHeaderListSize);
        this.settingsReceived = false;
    }

    // ─── append (called by Http3FrameToHttpDecoder) ───────────────────────────────

    /** Marks the SETTINGS frame as received. */
    void markSettingsReceived() {
        this.settingsReceived = true;
    }

    /** Returns or creates the stream for the given stream ID. */
    Http3Stream getOrCreateStream(long streamId) {
        return streams.computeIfAbsent(streamId, id -> new Http3Stream(id));
    }

    /** Returns the stream for the given stream ID, or {@code null} if absent. */
    Http3Stream getStream(long streamId) {
        return streams.get(streamId);
    }

    /** Closes and releases the stream for the given stream ID. */
    void closeStream(long streamId) {
        Http3Stream stream = streams.remove(streamId);
        if (stream != null) {
            stream.state(Http3StreamState.CLOSED);
            stream.release();
        }
    }

    /** Decodes a QPACK-compressed header block. */
    HttpHeaders decodeHeaders(byte[] data, int offset, int length) {
        return qpackDecoder.decode(data, offset, length);
    }

    /** Records a completed request stream ID for later response association. */
    void offerResponseStreamId(long streamId) {
        responseStreamIdQueue.offer(streamId);
    }

    /**
     * Applies a remote SETTINGS parameter.
     * Reserved settings are silently ignored per RFC 9114 §7.2.4.
     */
    void applyRemoteSetting(long settingId, long settingValue) {
        if (!Http3Settings.isReservedSetting(settingId)) {
            remoteSettings.applySetting(settingId, settingValue);
        }
    }

    // ─── poll (called by Duplexe on SND cycle) ────────────────────────────────────

    /** Polls the next response stream ID. Returns -1 if the queue is empty. */
    long pollResponseStreamId() {
        Long id = this.responseStreamIdQueue.poll();
        return id != null ? id : -1;
    }

    // ─── state view (read-only, for Http3ContextImpl) ────────────────────────────

    /** Returns {@code true} if the SETTINGS frame has been received. */
    boolean isSettingsReceived() {
        return settingsReceived;
    }

    /** Returns the highest stream ID currently tracked. */
    long lastStreamId() {
        long max = 0;
        for (Long id : this.streams.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    /** Returns the remote peer's negotiated {@code SETTINGS_MAX_FIELD_SECTION_SIZE}. */
    long maxFieldSectionSize() {
        return remoteSettings.maxFieldSectionSize();
    }

    /** Returns the remote peer's negotiated {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. */
    long qpackMaxTableCapacity() {
        return remoteSettings.qpackMaxTableCapacity();
    }

    /** Returns the remote peer's negotiated {@code SETTINGS_QPACK_BLOCKED_STREAMS}. */
    long qpackBlockedStreams() {
        return remoteSettings.qpackBlockedStreams();
    }

    // ─── release ───────────────────────────────────────────────────────────────────

    /** Releases all stream resources on connection close. */
    void releaseAll() {
        for (Http3Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }
}
