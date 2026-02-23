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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * A server-side HTTP/2 codec that combines frame-level and semantic-level
 * handlers into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf →[FrameDecoder]→ Http2Frame →[FrameToHttpDecoder]→ HttpObject<br>
 * SND direction: HttpObject →[HttpToFrameEncoder]→ Http2Frame →[FrameEncoder]→ ByteBuf
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * codec, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h2", new Http2ServerDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http2ServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private final Http2FrameDecoder             frameDecoder;
    private final Http2FrameToHttpDecoder       frameToHttpDecoder;
    private final HttpObjectToHttp2FrameEncoder httpToFrameEncoder;
    private final Http2FrameEncoder             frameEncoder;

    /** Reusable bridge queue for chaining frame-level and semantic-level handlers. */
    private final Http2FrameBridgeQueue bridgeQueue;
    private       boolean               serverPrefaceSent;
    /** Initial flow control window size for both stream-level and connection-level. */
    private       int                   initialWindowSize = 65535;

    /** Creates a server-side HTTP/2 codec with default HPACK settings (tableSize=4096, maxHeaderListSize=8192). */
    public Http2ServerDuplexe() {
        this.frameDecoder = new Http2FrameDecoder(true);
        this.frameToHttpDecoder = new Http2FrameToHttpDecoder(true);
        this.httpToFrameEncoder = new HttpObjectToHttp2FrameEncoder(true);
        this.frameEncoder = new Http2FrameEncoder();
        this.bridgeQueue = new Http2FrameBridgeQueue();
    }

    /**
     * Creates a server-side HTTP/2 codec with custom HPACK settings.
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 8192)
     */
    public Http2ServerDuplexe(int maxHeaderTableSize, int maxHeaderListSize) {
        this.frameDecoder = new Http2FrameDecoder(true);
        this.frameToHttpDecoder = new Http2FrameToHttpDecoder(true, maxHeaderTableSize, maxHeaderListSize);
        this.httpToFrameEncoder = new HttpObjectToHttp2FrameEncoder(true, maxHeaderTableSize);
        this.frameEncoder = new Http2FrameEncoder();
        this.bridgeQueue = new Http2FrameBridgeQueue();
    }

    /**
     * Creates a server-side HTTP/2 codec with custom HPACK settings and flow control window.
     * <p>
     * The {@code initialWindowSize} controls both the SETTINGS INITIAL_WINDOW_SIZE
     * (stream-level) and the connection-level flow control window (via proactive
     * WINDOW_UPDATE on stream 0). Setting this to at least the max content length
     * prevents flow control deadlocks during large uploads.
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 8192)
     * @param initialWindowSize flow control window size in bytes (default: 65535)
     */
    public Http2ServerDuplexe(int maxHeaderTableSize, int maxHeaderListSize, int initialWindowSize) {
        this(maxHeaderTableSize, maxHeaderListSize);
        this.initialWindowSize = Math.max(initialWindowSize, 65535);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(context);
        this.frameToHttpDecoder.onInit(context);
        this.httpToFrameEncoder.onInit(context);
        this.frameEncoder.onInit(context);
        context.context(Http2Context.class, this.frameToHttpDecoder.createContext());
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.frameDecoder.onActive(context);
        this.frameToHttpDecoder.onActive(context);
        this.httpToFrameEncoder.onActive(context);
        this.frameEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV: ByteBuf → Http2Frame → HttpObject
            bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, bridgeQueue);
            this.frameToHttpDecoder.onMessage(context, bridgeQueue, rcvDown);
            return ProtoStatus.Next;
        } else {
            bridgeQueue.clear();

            // Server connection preface: SETTINGS frame (RFC 9113 §3.4)
            // Only added when response data is available, ensuring SETTINGS is always
            // the absolute first frame on the wire.
            if (!this.serverPrefaceSent && sndUp.hasMore()) {
                bridgeQueue.offerMessage(buildServerSettingsFrame());
                // Proactively expand connection-level flow control window (RFC 9113 §6.9.2)
                if (this.initialWindowSize > 65535) {
                    bridgeQueue.offerMessage(buildWindowUpdate(0, this.initialWindowSize - 65535));
                }
                this.serverPrefaceSent = true;
            }

            // SETTINGS ACK (acknowledging client's SETTINGS)
            if (this.frameToHttpDecoder.consumeSettingsAck()) {
                bridgeQueue.offerMessage(Http2Frame.settingsAck());
            }

            // PING ACK
            byte[] pingPayload;
            while ((pingPayload = this.frameToHttpDecoder.pollPendingPingAck()) != null) {
                bridgeQueue.offerMessage(Http2Frame.pingAck(pingPayload));
            }

            // WINDOW_UPDATE (flow control replenishment for received DATA frames)
            // Sent in auto-SND to prevent flow control deadlock during uploads.
            Http2Frame windowUpdate;
            while ((windowUpdate = this.frameToHttpDecoder.pollPendingWindowUpdate()) != null) {
                bridgeQueue.offerMessage(windowUpdate);
            }

            // Response data: append to same bridgeQueue if available
            if (sndUp.hasMore()) {
                int nextStreamId = this.frameToHttpDecoder.pollResponseStreamId();
                if (nextStreamId > 0) {
                    this.httpToFrameEncoder.setResponseStreamId(nextStreamId);
                }
                // HttpObject → Http2Frame (appended after SETTINGS + control frames)
                this.httpToFrameEncoder.onMessage(context, sndUp, bridgeQueue);
            }

            // Single-pass encoding: all frames in one frameEncoder call
            if (bridgeQueue.hasMore()) {
                this.frameEncoder.onMessage(context, bridgeQueue, sndDown);
            }

            return ProtoStatus.Next;
        }
    }

    /**
     * Builds the server SETTINGS frame (connection preface) as an Http2Frame.
     * Sends: MAX_CONCURRENT_STREAMS=100, INITIAL_WINDOW_SIZE={@link #initialWindowSize}, ENABLE_PUSH=0
     */
    private Http2Frame buildServerSettingsFrame() {
        // 3 settings x 6 bytes each = 18 bytes payload
        byte[] payload = new byte[18];
        int offset = 0;

        // SETTINGS_MAX_CONCURRENT_STREAMS (0x03) = 100
        payload[offset++] = 0x00;
        payload[offset++] = (byte) Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS;
        payload[offset++] = 0x00;
        payload[offset++] = 0x00;
        payload[offset++] = 0x00;
        payload[offset++] = 100;

        // SETTINGS_INITIAL_WINDOW_SIZE (0x04) = initialWindowSize
        int ws = this.initialWindowSize;
        payload[offset++] = 0x00;
        payload[offset++] = (byte) Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE;
        payload[offset++] = (byte) ((ws >> 24) & 0x7F);
        payload[offset++] = (byte) ((ws >> 16) & 0xFF);
        payload[offset++] = (byte) ((ws >> 8) & 0xFF);
        payload[offset++] = (byte) (ws & 0xFF);

        // SETTINGS_ENABLE_PUSH (0x02) = 0
        payload[offset++] = 0x00;
        payload[offset++] = (byte) Http2Settings.SETTINGS_ENABLE_PUSH;
        payload[offset++] = 0x00;
        payload[offset++] = 0x00;
        payload[offset++] = 0x00;
        payload[offset] = 0x00;

        return Http2Frame.settings(Http2Flags.NONE, payload);
    }

    /** Builds a WINDOW_UPDATE frame with the given stream ID and increment. */
    private static Http2Frame buildWindowUpdate(int streamId, int increment) {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((increment >> 24) & 0x7F);
        payload[1] = (byte) ((increment >> 16) & 0xFF);
        payload[2] = (byte) ((increment >> 8) & 0xFF);
        payload[3] = (byte) (increment & 0xFF);
        return Http2Frame.windowUpdate(streamId, payload);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onError(context, e, eh);
        } else {
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.frameDecoder.onClose(context);
        this.frameToHttpDecoder.onClose(context);
        this.httpToFrameEncoder.onClose(context);
        this.frameEncoder.onClose(context);
    }
}
