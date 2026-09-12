/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.neta.codec.http.*;
/**
 * Connection-level state container shared by the HTTP/2 message layer.
 * <p>
 * Operations are divided into four categories:
 * <ul>
 *   <li><b>init</b>: the constructor and configuration injected once by the duplex entry.</li>
 *   <li><b>append</b>: called only by {@link Http2ObjectDecoder} to extend state as frames arrive.</li>
 *   <li><b>poll</b>: called during the SND cycle by the message duplexer or a higher-level HTTP adapter to extract queued control frames.</li>
 *   <li><b>release</b>: called when the connection closes to release resources.</li>
 * </ul>
 * All fields are private, and callers must not access internal collections or child objects directly.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-28
 */
class Http2DecoderContent {
    private final Queue<Long>                responseStreamIdQueue   = new LinkedList<>();
    private final Queue<byte[]>              pendingPingAcks         = new LinkedList<>();
    private final Queue<PendingWindowUpdate> pendingWindowUpdates    = new LinkedList<>();
    private final Map<Long, Http2Stream>     streams                 = new HashMap<>();
    private final HpackDecoder               hpackDecoder;
    private final Http2Settings              localSettings;
    private final Http2Settings              remoteSettings          = new Http2Settings();
    private boolean                          prefaceReceived;
    private boolean                          pendingSettingsAck;
    private long                             openHeaderBlockStreamId = -1;
    private int                              openHeaderBlockType     = -1;
    private long                             openPromisedStreamId    = -1;
    private long                             lastEmittedStreamId     = 0;

    /**
     * Creates the decoder-side connection state container.
     * @param serverMode whether the current endpoint runs in server mode; in server mode the preface starts as not received
     * @param localSettings the HTTP/2 settings advertised by the local endpoint; if {@code null}, an empty default configuration is used
     */
    public Http2DecoderContent(boolean serverMode, Http2Settings localSettings) {
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : new Http2Settings();
        this.hpackDecoder = new HpackDecoder((int) this.localSettings.headerTableSize(), normalizeHeaderListSize(this.localSettings.maxHeaderListSize()));
        this.prefaceReceived = !serverMode;
    }

    // append (called by Http2ObjectDecoder)

    /**
     * Marks the connection preface as received.
     */
    public void markPrefaceReceived() {
        this.prefaceReceived = true;
    }

    /**
     * Returns the stream for the given stream ID, creating it if necessary.
     */
    public Http2Stream getOrCreateStream(long streamId) {
        return streams.computeIfAbsent(streamId, Http2Stream::new);
    }

    /**
     * Returns the stream for the given stream ID, or {@code null} if it does not exist.
     */
    public Http2Stream getStream(long streamId) {
        return streams.get(streamId);
    }

    /**
     * Closes and releases the stream for the given stream ID.
     */
    public void closeStream(long streamId) {
        Http2Stream stream = streams.remove(streamId);
        if (stream != null) {
            stream.state(Http2StreamState.CLOSED);
            stream.release();
        }
        if (this.openHeaderBlockStreamId == streamId) {
            this.openHeaderBlockStreamId = -1;
            this.openHeaderBlockType = -1;
            this.openPromisedStreamId = -1;
        }
    }

    /**
     * Removes queued response stream-ID entries for the given stream from the FIFO queue.
     */
    public void removeFromResponseQueue(long streamId) {
        responseStreamIdQueue.removeIf(id -> id == streamId);
    }

    /**
     * Decodes an HPACK-compressed header block.
     */
    public DefaultHttpHeaders decodeHeaders(byte[] data, int offset, int length) {
        return hpackDecoder.decode(data, offset, length);
    }

    /**
     * Builds a request or response start-line from a decoded HTTP/2 header block.
     */
    public HttpObject newStartLine(long streamId, HttpHeaders headers) {
        String status = headers.getString(HttpHeaderNames.PSEUDO_STATUS);
        if (status != null) {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.valueOf(Integer.parseInt(status)));
            response.streamId(streamId);
            return response;
        }

        String method = headers.getString(HttpHeaderNames.PSEUDO_METHOD);
        String path = headers.getString(HttpHeaderNames.PSEUDO_PATH);
        if (method == null || method.trim().isEmpty() || path == null || path.trim().isEmpty()) {
            String msg = "HTTP/2: missing required pseudo-header " + HttpHeaderNames.PSEUDO_METHOD + " or " + HttpHeaderNames.PSEUDO_PATH;
            throw new HttpBadRequestException(msg);
        }

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.valueOf(method), path);
        request.streamId(streamId);
        return request;
    }

    /**
     * Queues a PING ACK payload that should be sent to the remote peer.
     */
    public void offerPingAck(byte[] payload) {
        pendingPingAcks.offer(payload);
    }

    /**
     * Queues a WINDOW_UPDATE request that should be sent to the remote peer.
     */
    public void offerWindowUpdate(long streamId, int increment) {
        if (increment > 0) {
            pendingWindowUpdates.offer(new PendingWindowUpdate(streamId, increment));
        }
    }

    /**
     * Records the stream ID of a completed request for later response association.
     */
    public void offerResponseStreamId(long streamId) {
        responseStreamIdQueue.offer(streamId);
    }

    /**
     * Marks that the next SND cycle must send a SETTINGS ACK.
     */
    public void markSettingsAckPending() {
        this.pendingSettingsAck = true;
    }

    /**
     * Marks that the given stream currently owns an open fragmented header block.
     */
    public void openHeaderBlockOn(long streamId, int frameType, long promisedStreamId) {
        this.openHeaderBlockStreamId = streamId;
        this.openHeaderBlockType = frameType;
        this.openPromisedStreamId = promisedStreamId;
    }

    /**
     * Clears the marker for the currently active fragmented header block.
     */
    public void closeOpenHeaderBlock() {
        this.openHeaderBlockStreamId = -1;
        this.openHeaderBlockType = -1;
        this.openPromisedStreamId = -1;
    }

    /**
     * Applies a remote SETTINGS parameter. If it includes {@code SETTINGS_HEADER_TABLE_SIZE}, the HPACK decoder table capacity is updated accordingly.
     */
    public void applyRemoteSetting(int id, long value) {
        remoteSettings.applySetting(id, value);
        hpackDecoder.setMaxHeaderTableSize((int) remoteSettings.headerTableSize());
    }

    /**
     * Records the stream ID of the most recently emitted {@link net.hasor.neta.codec.http.HttpObject}.
     */
    public void setLastEmittedStreamId(long streamId) {
        this.lastEmittedStreamId = streamId;
    }

    /**
     * Returns the initial flow-control window size configured by the server.
     */
    public int serverInitialWindowSize() {
        return this.localSettings.initialWindowSize();
    }

    /**
     * Returns the initial flow-control window size negotiated with the remote peer.
     */
    public int remoteInitialWindowSize() {
        return remoteSettings.initialWindowSize();
    }

    /**
     * Returns {@code true} if a HEADERS block is still waiting for CONTINUATION frames.
     */
    public boolean hasOpenHeaderBlock() {
        return this.openHeaderBlockStreamId > 0;
    }

    /**
     * Returns the stream ID that currently owns the open header block, or -1 if none exists.
     */
    public long openHeaderBlockStreamId() {
        return this.openHeaderBlockStreamId;
    }

    /**
     * Returns the frame type of the currently open header block, or -1 if none exists.
     */
    public int openHeaderBlockType() {
        return this.openHeaderBlockType;
    }

    /**
     * Returns the promised stream ID associated with the currently open PUSH_PROMISE block, or -1 if none exists.
     */
    public long openPromisedStreamId() {
        return this.openPromisedStreamId;
    }

    // poll (called by Http2ObjectEncoder during the SND cycle)

    /**
     * Polls the next response stream ID, or -1 when the queue is empty.
     */
    public long pollResponseStreamId() {
        Long id = this.responseStreamIdQueue.poll();
        return id != null ? id : -1;
    }

    /**
     * Polls the next pending PING ACK payload, or {@code null} if none exists.
     */
    public byte[] pollPendingPingAck() {
        return this.pendingPingAcks.poll();
    }

    /**
     * Polls the next pending WINDOW_UPDATE request, or {@code null} if none exists.
     */
    public PendingWindowUpdate pollPendingWindowUpdate() {
        return this.pendingWindowUpdates.poll();
    }

    /**
     * Checks and atomically consumes the pending SETTINGS ACK marker.
     */
    public boolean consumeSettingsAck() {
        if (this.pendingSettingsAck) {
            this.pendingSettingsAck = false;
            return true;
        }
        return false;
    }

    // state view (read-only, used by Http2ContextImpl)

    /**
     * Returns {@code true} when the connection preface has been received.
     */
    public boolean isPrefaceReceived() {
        return prefaceReceived;
    }

    /**
     * Returns the highest stream ID currently being tracked.
     */
    public long lastStreamId() {
        long max = 0;
        for (Long id : this.streams.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    /**
     * Returns the highest stream ID initiated by the remote peer.
     */
    public long lastRemoteInitiatedStreamId(boolean serverMode) {
        long max = 0;
        int remoteParity = serverMode ? 1 : 0;
        for (Long id : this.streams.keySet()) {
            if (id != null && id > max && (id & 1) == remoteParity) {
                max = id;
            }
        }
        return max;
    }

    /**
     * Returns the stream ID of the most recently emitted HttpObject.
     */
    public long lastEmittedStreamId() {
        return lastEmittedStreamId;
    }

    /**
     * Returns the {@code SETTINGS_MAX_CONCURRENT_STREAMS} value negotiated with the remote peer.
     */
    public long remoteMaxConcurrentStreams() {
        return remoteSettings.maxConcurrentStreams();
    }

    /**
     * Returns the {@code SETTINGS_MAX_FRAME_SIZE} value negotiated with the remote peer.
     */
    public int remoteMaxFrameSize() {
        return remoteSettings.maxFrameSize();
    }

    /**
     * Returns the local settings snapshot currently held by the decoder side.
     * <p>
     * The returned value is a copy, so caller-side modifications do not affect the connection state.
     */
    public Http2Settings localSettings() {
        return new Http2Settings(this.localSettings);
    }

    // release

    /**
     * Releases all stream resources when the connection closes.
     */
    public void releaseAll() {
        for (Http2Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }

    private static int normalizeHeaderListSize(long maxHeaderListSize) {
        if (maxHeaderListSize <= 0 || maxHeaderListSize > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) maxHeaderListSize;
    }

    static final class PendingWindowUpdate {
        private final long streamId;
        private final int  increment;

        PendingWindowUpdate(long streamId, int increment) {
            this.streamId = streamId;
            this.increment = increment;
        }

        long streamId() {
            return this.streamId;
        }

        int increment() {
            return this.increment;
        }
    }
}
