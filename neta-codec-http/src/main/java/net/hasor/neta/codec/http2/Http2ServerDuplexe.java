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
package net.hasor.neta.codec.http2;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * A server-side HTTP/2 codec that combines {@link Http2FrameDecoder} and
 * {@link Http2FrameEncoder} into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf → HttpObject (HTTP/2 frame decoding → HttpRequest/HttpContent)
 * SND direction: HttpObject → ByteBuf (HttpResponse/HttpContent → HTTP/2 frame encoding)
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
    private static final int               FRAME_HEADER_SIZE = 9;
    private final        Http2FrameDecoder decoder;
    private final        Http2FrameEncoder encoder;
    private              boolean           serverPrefaceSent;

    /** Creates a server-side HTTP/2 codec with default HPACK settings (tableSize=4096, maxHeaderListSize=8192). */
    public Http2ServerDuplexe() {
        this.decoder = new Http2FrameDecoder(true);
        this.encoder = new Http2FrameEncoder(true);
    }

    /**
     * Creates a server-side HTTP/2 codec with custom HPACK settings.
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 8192)
     */
    public Http2ServerDuplexe(int maxHeaderTableSize, int maxHeaderListSize) {
        this.decoder = new Http2FrameDecoder(true, maxHeaderTableSize, maxHeaderListSize);
        this.encoder = new Http2FrameEncoder(true, maxHeaderTableSize);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.decoder.onInit(context);
        this.encoder.onInit(context);
        context.context(Http2Context.class, this.decoder.createContext());
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
        // Server connection preface (SETTINGS frame) is NOT sent here.
        // In routed pipelines (branch mode), context.sendData() goes through the main
        // pipeline's SND lifecycle which cannot resolve branch handler stackNames.
        // To guarantee correct ordering (SETTINGS before any response), SETTINGS is deferred
        // to the first SND lifecycle that carries actual response data (see onMessage SND path).
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // Decode inbound HTTP/2 frames (preface, SETTINGS, HEADERS, DATA, etc.)
            ProtoStatus status = this.decoder.onMessage(context, rcvUp, rcvDown);
            return status;
        } else {
            // SND lifecycle without response data (triggered during RCV processing):
            // Skip all SND output to avoid write ordering issues in routed pipelines.
            // SETTINGS, SETTINGS_ACK, PING_ACK will be sent with the first response.
            if (!sndUp.hasMore()) {
                return ProtoStatus.Next;
            }

            // Server connection preface: SETTINGS frame (RFC 9113 §3.4)
            // Sent alongside the first response to ensure correct write ordering.
            // In routed pipelines, this guarantees SETTINGS reaches the socket
            // in the same write batch as the response, before HEADERS/DATA.
            if (!this.serverPrefaceSent) {
                sndDown.offerMessage(buildServerSettingsFrame(context));
                this.serverPrefaceSent = true;
            }

            // Always check for pending SETTINGS ACK (client may send SETTINGS at any time)
            if (this.decoder.consumeSettingsAck()) {
                sndDown.offerMessage(buildSettingsAckFrame(context));
            }

            // Always check for pending PING ACK (connection health, RFC 9113 §6.7)
            byte[] pingPayload;
            while ((pingPayload = this.decoder.pollPendingPingAck()) != null) {
                sndDown.offerMessage(buildPingAckFrame(context, pingPayload));
            }

            // Poll the correct stream ID from the FIFO queue to ensure
            // responses are associated with their matching request stream.
            // This fixes multiplexing: when multiple requests arrive in one TCP segment,
            // the old getLastEmittedStreamId() returned only the LAST decoded stream ID,
            // causing all responses to be sent on the wrong stream.
            int nextStreamId = this.decoder.pollResponseStreamId();
            if (nextStreamId > 0) {
                this.encoder.setResponseStreamId(nextStreamId);
            }
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    /**
     * Builds the server SETTINGS frame (connection preface).
     * Sends: MAX_CONCURRENT_STREAMS=100, INITIAL_WINDOW_SIZE=65535, ENABLE_PUSH=0
     */
    private ByteBuf buildServerSettingsFrame(ProtoContext context) {
        // 3 settings x 6 bytes each = 18 bytes payload
        int payloadLength = 18;
        ByteBuf buf = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLength);

        // Frame header: length=18, type=SETTINGS(0x04), flags=0, streamId=0
        buf.writeByte((byte) ((payloadLength >>> 16) & 0xFF));
        buf.writeByte((byte) ((payloadLength >>> 8) & 0xFF));
        buf.writeByte((byte) (payloadLength & 0xFF));
        buf.writeByte((byte) Http2FrameType.SETTINGS);
        buf.writeByte((byte) 0); // no flags
        buf.writeInt32(0); // stream 0

        // SETTINGS_MAX_CONCURRENT_STREAMS (0x03) = 100
        buf.writeInt16((short) Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS);
        buf.writeInt32(100);

        // SETTINGS_INITIAL_WINDOW_SIZE (0x04) = 65535
        buf.writeInt16((short) Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE);
        buf.writeInt32(65535);

        // SETTINGS_ENABLE_PUSH (0x02) = 0 (disabled for server)
        buf.writeInt16((short) Http2Settings.SETTINGS_ENABLE_PUSH);
        buf.writeInt32(0);

        buf.markWriter();
        return buf;
    }

    /**
     * Builds a PING ACK frame with the specified opaque data (RFC 9113 §6.7).
     * Must echo back the exact 8-byte payload received in the PING frame.
     */
    private ByteBuf buildPingAckFrame(ProtoContext context, byte[] opaqueData) {
        int payloadLength = 8;
        ByteBuf buf = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLength);

        // Frame header: length=8, type=PING(0x06), flags=ACK(0x01), streamId=0
        buf.writeByte((byte) 0);
        buf.writeByte((byte) 0);
        buf.writeByte((byte) payloadLength);
        buf.writeByte((byte) Http2FrameType.PING);
        buf.writeByte((byte) Http2Flags.ACK);
        buf.writeInt32(0); // stream 0

        // Echo opaque data
        buf.writeBytes(opaqueData, 0, 8);

        buf.markWriter();
        return buf;
    }

    /**
     * Builds a SETTINGS ACK frame (empty SETTINGS with ACK flag).
     */
    private ByteBuf buildSettingsAckFrame(ProtoContext context) {
        ByteBuf buf = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE);

        // Frame header: length=0, type=SETTINGS(0x04), flags=ACK(0x01), streamId=0
        buf.writeByte((byte) 0);
        buf.writeByte((byte) 0);
        buf.writeByte((byte) 0);
        buf.writeByte((byte) Http2FrameType.SETTINGS);
        buf.writeByte((byte) Http2Flags.ACK);
        buf.writeInt32(0); // stream 0

        buf.markWriter();
        return buf;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}
