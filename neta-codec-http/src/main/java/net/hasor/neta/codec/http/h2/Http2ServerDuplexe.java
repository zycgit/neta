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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.event.HttpStreamResetEvent;

/**
 * A server-side HTTP/2 codec that combines frame-level and semantic-level
 * handlers into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf →[FrameDecoder]→ Http2Frame →[FrameToMessageDecoder]→ Http2Message<br>
 * SND direction: Http2Message →[MessageToFrameEncoder]→ Http2Frame →[FrameEncoder]→ ByteBuf
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h2", new Http2ServerDuplexe());
 * </pre>
 */
public class Http2ServerDuplexe implements ProtoDuplexer<ByteBuf, Http2Message, Http2Message, ByteBuf> {
    private static final Logger                     logger             = Logger.getLogger(Http2ServerDuplexe.class);
    private final        Http2FrameDecoder          frameDecoder;
    private final        Http2FrameToMessageDecoder frameToMessageDecoder;
    private final        Http2MessageToFrameEncoder messageToFrameEncoder;
    private final        Http2FrameEncoder          frameEncoder;
    private final        Http2FrameBridgeQueue      bridgeQueue        = new Http2FrameBridgeQueue();
    private final        Http2MessageBridgeQueue    messageBridgeQueue = new Http2MessageBridgeQueue();
    private              boolean                    serverPrefaceSent;
    /** Initial flow control window size for both stream-level and connection-level. */
    private              int                        initialWindowSize  = 65535;

    /** Creates a server-side HTTP/2 codec with default HPACK settings (tableSize=4096, maxHeaderListSize=8192). */
    public Http2ServerDuplexe() {
        this.frameDecoder = new Http2FrameDecoder(true);
        this.frameToMessageDecoder = new Http2FrameToMessageDecoder(true);
        this.messageToFrameEncoder = new Http2MessageToFrameEncoder(true);
        this.frameEncoder = new Http2FrameEncoder();
    }

    /**
     * Creates a server-side HTTP/2 codec with custom HPACK settings.
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 8192)
     */
    public Http2ServerDuplexe(int maxHeaderTableSize, int maxHeaderListSize) {
        this.frameDecoder = new Http2FrameDecoder(true);
        this.frameToMessageDecoder = new Http2FrameToMessageDecoder(true, maxHeaderTableSize, maxHeaderListSize);
        this.messageToFrameEncoder = new Http2MessageToFrameEncoder(true, maxHeaderTableSize);
        this.frameEncoder = new Http2FrameEncoder();
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

    /** Maps a semantic {@link HttpStreamResetEvent} error-code sentinel to the corresponding HTTP/2 wire error code (RFC 9113 §7). */
    private static long resolveH2ErrorCode(long code) {
        if (code == HttpStreamResetEvent.CANCEL) {
            return Http2ErrorCode.CANCEL;
        } else if (code == HttpStreamResetEvent.INTERNAL_ERROR) {
            return Http2ErrorCode.INTERNAL_ERROR;
        } else if (code == HttpStreamResetEvent.REFUSED) {
            return Http2ErrorCode.REFUSED_STREAM;
        } else if (code < 0) {
            return Http2ErrorCode.INTERNAL_ERROR; // unknown sentinel → INTERNAL_ERROR
        }
        return code;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(name, rcvSize, context);
        this.frameToMessageDecoder.onInit(name, rcvSize, context);
        this.messageToFrameEncoder.onInit(name, sndSize, context);
        this.frameEncoder.onInit(name, sndSize, context);
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        decoderState.setServerInitialWindowSize(this.initialWindowSize);
        context.context(Http2Context.class, new Http2ContextImpl(true, decoderState));
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.frameDecoder.onActive(context);
        this.frameToMessageDecoder.onActive(context);
        this.messageToFrameEncoder.onActive(context);
        this.frameEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Http2Message> rcvDown,//
            ProtoRcvQueue<Http2Message> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV: ByteBuf → Http2Frame → Http2Message
            this.bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, this.bridgeQueue);
            this.frameToMessageDecoder.onMessage(context, this.bridgeQueue, rcvDown);

            // Server connection preface (SETTINGS frame): send during RCV processing.
            // Writing SETTINGS to sndDown (headSndDown) during RCV guarantees it is the
            // FIRST bytes in the output queue — before any inline sendData() output or
            // auto-SND control frames (SETTINGS_ACK, WINDOW_UPDATE). This satisfies
            // RFC 9113 §3.4 which mandates SETTINGS as the server's first frame.
            if (!this.serverPrefaceSent) {
                this.bridgeQueue.clear();
                this.messageBridgeQueue.clear();
                this.messageBridgeQueue.offerMessage(buildServerSettingsMessage());
                if (this.initialWindowSize > 65535) {
                    this.messageBridgeQueue.offerMessage(new Http2WindowUpdateMessage(0, this.initialWindowSize - 65535));
                }
                this.messageToFrameEncoder.onMessage(context, this.messageBridgeQueue, this.bridgeQueue);
                this.frameEncoder.onMessage(context, this.bridgeQueue, sndDown);
                this.serverPrefaceSent = true;
                if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                    logger.info("[H2-RCV] server preface sent, initialWindowSize=" + this.initialWindowSize);
                }
            }

            return ProtoStatus.Next;
        } else {
            Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
            this.bridgeQueue.clear();
            this.messageBridgeQueue.clear();

            // SETTINGS ACK (acknowledging client's SETTINGS)
            if (decoderState.consumeSettingsAck()) {
                this.messageBridgeQueue.offerMessage(new Http2SettingsMessage(true, null));
            }

            // PING ACK
            byte[] pingPayload;
            while ((pingPayload = decoderState.pollPendingPingAck()) != null) {
                this.messageBridgeQueue.offerMessage(new Http2PingMessage(true, pingPayload));
            }

            // WINDOW_UPDATE (flow control replenishment for received DATA frames)
            // Sent in auto-SND to prevent flow control deadlock during uploads.
            Http2Frame windowUpdate;
            int wuCount = 0;
            while ((windowUpdate = decoderState.pollPendingWindowUpdate()) != null) {
                this.messageBridgeQueue.offerMessage(new Http2WindowUpdateMessage(windowUpdate.streamId(), parseWindowUpdateIncrement(windowUpdate.payload(), windowUpdate.payloadOffset())));
                wuCount++;
            }
            if (wuCount > 0 && context.getConfig() != null && context.getConfig().isPrintLog()) {
                logger.info("[H2-SND] polled " + wuCount + " WINDOW_UPDATE frames");
            }

            // Response data and app-generated control messages.
            if (sndUp.hasMore()) {
                int nextStreamId = 0;
                Http2Message peek = sndUp.peekMessage();
                if (peek != null) {
                    nextStreamId = peek.streamId();
                }
                if (nextStreamId <= 0) {
                    nextStreamId = decoderState.pollResponseStreamId();
                }
                if (nextStreamId > 0) {
                    Http2EncoderContent encoderState = context.context(Http2EncoderContent.class);
                    encoderState.setCurrentStreamId(nextStreamId);
                }
                this.messageBridgeQueue.offerMessage(sndUp);
            }

            if (this.messageBridgeQueue.hasMore()) {
                this.messageToFrameEncoder.onMessage(context, this.messageBridgeQueue, this.bridgeQueue);
            }

            // Single-pass encoding: all frames in one frameEncoder call
            if (this.bridgeQueue.hasMore()) {
                this.frameEncoder.onMessage(context, this.bridgeQueue, sndDown);
            }

            return ProtoStatus.Next;
        }
    }

    /**
     * Builds the server SETTINGS frame (connection preface) as an Http2Frame.
     * Sends: MAX_CONCURRENT_STREAMS=100, INITIAL_WINDOW_SIZE={@link #initialWindowSize}, ENABLE_PUSH=0
     */
    private Http2SettingsMessage buildServerSettingsMessage() {
        java.util.Map<Integer, Long> settings = new java.util.LinkedHashMap<>();
        settings.put(Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS, 100L);
        settings.put(Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE, (long) this.initialWindowSize);
        settings.put(Http2Settings.SETTINGS_ENABLE_PUSH, 0L);
        return new Http2SettingsMessage(false, settings);
    }

    private static int parseWindowUpdateIncrement(byte[] payload, int offset) {
        return ((payload[offset] & 0x7F) << 24) | ((payload[offset + 1] & 0xFF) << 16) | ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (event.getEventType() == HttpStreamResetEvent.class) {
            HttpStreamResetEvent reset = (HttpStreamResetEvent) event.getData();
            long sid = reset.streamId();
            if (sid > 0 && sid <= Integer.MAX_VALUE) {
                int streamId = (int) sid;
                long errorCode = resolveH2ErrorCode(reset.errorCode());
                // Clean up stream state and orphaned response-queue entry
                Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
                decoderState.closeStream(streamId);
                decoderState.removeFromResponseQueue(streamId);
                context.sendData(new Http2ResetStreamMessage(streamId, errorCode));
                if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                    logger.info("[H2-SND] ch=" + context.getChannel().getChannelId() + " RST_STREAM queued stream=" + streamId + " errorCode=0x" + Long.toHexString(errorCode) + " (via UserEvent)");
                }
            }
            return false; // event consumed
        }
        return true;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onError(context, e, eh);
        } else {
            // SND-side error: reset only the current stream rather than closing the entire connection.
            Http2EncoderContent encoderState = context.context(Http2EncoderContent.class);
            int streamId = encoderState != null ? encoderState.currentStreamId() : 0;
            if (streamId > 0) {
                try {
                    Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
                    decoderState.closeStream(streamId);
                    decoderState.removeFromResponseQueue(streamId);
                    context.sendData(new Http2ResetStreamMessage(streamId, Http2ErrorCode.INTERNAL_ERROR));
                    logger.warn("[H2-SND] ch=" + context.getChannel().getChannelId() + " encoding error on stream=" + streamId + ", queued RST_STREAM(INTERNAL_ERROR): " + e.getMessage());
                    return ProtoStatus.Next;
                } catch (Throwable t) {
                    // Fall through to connection-level error handling
                }
            }
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.frameDecoder.onClose(context);
        this.frameToMessageDecoder.onClose(context);
        this.messageToFrameEncoder.onClose(context);
        this.frameEncoder.onClose(context);
    }
}