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
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpProtocolOutOfBoundsException;
import net.hasor.neta.codec.http.HttpProtocolStateException;

/**
 * HTTP/2 binary frame decoder that converts raw bytes into {@link Http2Frame} objects.
 * <p>
 * This decoder implements the HTTP/2 binary framing layer defined in RFC 9113.
 * It handles the connection preface, parses the 9-byte frame header, extracts
 * the payload, and emits {@link Http2Frame} instances for downstream semantic
 * processing by {@link Http2ObjectDecoder}.
 * <p>
 * <b>Decode path:</b> {@code ByteBuf → Http2Frame}; semantic conversion to {@link net.hasor.neta.codec.http.HttpObject}
 * happens later in {@link Http2ObjectDecoder}.
 * <p>
 * Frame format (RFC 9113, Section 4):
 * <pre>
 *   +-----------------------------------------------+
 *   |                 Length (24)                   |
 *   +---------------+---------------+---------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+---------------+
 *   |R|                 Stream Identifier (31)      |
 *   +=+=============================================+
 *   |                 Frame Payload (0...)          |
 *   +-----------------------------------------------+
 * </pre>
 * @see Http2Frame
 * @see Http2ObjectDecoder
 */
public class Http2FrameDecoder implements ProtoHandler<ByteBuf, Http2Frame> {
    private static final Logger  logger             = Logger.getLogger(Http2FrameDecoder.class);
    /** HTTP/2 connection preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" */
    private static final byte[]  CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    /** Frame header size: 9 bytes */
    private static final int     FRAME_HEADER_SIZE  = 9;
    private final        int     maxFrameSize;
    /** Reusable frame header buffer to avoid per-frame byte[9] allocation. */
    private final        byte[]  frameHeaderBuf     = new byte[FRAME_HEADER_SIZE];
    /** Reusable preface buffer. */
    private final        byte[]  prefaceCheckBuf    = new byte[24]; // "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    private              ByteBuf accumulator;
    private              boolean prefaceReceived;

    /**
     * Creates a new HTTP/2 frame decoder.
     * @param serverMode true for server-side (expects client preface), false for client-side
     */
    public Http2FrameDecoder(boolean serverMode) {
        this(serverMode, new Http2Settings());
    }

    public Http2FrameDecoder(boolean serverMode, Http2Settings settings) {
        Http2Settings localSettings = settings != null ? new Http2Settings(settings) : new Http2Settings();
        this.maxFrameSize = localSettings.maxFrameSize();
        this.prefaceReceived = !serverMode; // Client doesn't receive a preface
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        // Handle connection preface for server mode
        if (!this.prefaceReceived) {
            if (this.accumulator.readableBytes() < CONNECTION_PREFACE.length) {
                this.accumulator.markReader();
                return ProtoStatus.Next;
            }
            byte[] prefaceBytes = this.prefaceCheckBuf;
            this.accumulator.getBytes(0, prefaceBytes, 0, prefaceBytes.length);
            for (int i = 0; i < CONNECTION_PREFACE.length; i++) {
                if (prefaceBytes[i] != CONNECTION_PREFACE[i]) {
                    throw new HttpProtocolStateException(0, "HTTP/2: invalid connection preface");
                }
            }

            this.accumulator.skipReadableBytes(CONNECTION_PREFACE.length);
            this.prefaceReceived = true;
            if (context.getConfig().isPrintLog()) {
                long channelID = context.getChannel().getChannelId();
                logger.info("[H2-FRAME] channel=" + channelID + " connection preface received");
            }
        }

        // Decode frames
        while (this.accumulator.readableBytes() >= FRAME_HEADER_SIZE) {
            // Peek at header without consuming
            byte[] headerBuf = this.frameHeaderBuf;
            this.accumulator.getBytes(0, headerBuf, 0, FRAME_HEADER_SIZE);

            int payloadLength = ((headerBuf[0] & 0xFF) << 16) | ((headerBuf[1] & 0xFF) << 8) | (headerBuf[2] & 0xFF);
            int type = headerBuf[3] & 0xFF;
            int flags = headerBuf[4] & 0xFF;
            int streamId = ((headerBuf[5] & 0x7F) << 24) | ((headerBuf[6] & 0xFF) << 16) | ((headerBuf[7] & 0xFF) << 8) | (headerBuf[8] & 0xFF);

            // Validate frame size
            if (payloadLength > this.maxFrameSize) {
                String msg = "HTTP/2: frame size exceeds SETTINGS_MAX_FRAME_SIZE: " + payloadLength;
                throw new HttpProtocolOutOfBoundsException(streamId, msg);
            }

            int totalFrameSize = FRAME_HEADER_SIZE + payloadLength;
            if (this.accumulator.readableBytes() < totalFrameSize) {
                // Not enough data for the full frame; wait for more
                break;
            }

            // Skip frame header
            this.accumulator.skipReadableBytes(FRAME_HEADER_SIZE);

            // Read payload
            byte[] payload = new byte[payloadLength];
            if (payloadLength > 0) {
                this.accumulator.getBytes(0, payload, 0, payloadLength);
                this.accumulator.skipReadableBytes(payloadLength);
            }

            // Emit Http2Frame
            dst.offerMessage(new Http2Frame(type, flags, streamId, payload));
            if (context.getConfig().isPrintLog()) {
                long channelID = context.getChannel().getChannelId();
                logger.info("[H2-RCV-FRAME] ch=" + channelID + " " + Http2FrameType.name(type)//
                        + " flags=" + Http2Flags.describe(type, flags)//
                        + " stream=" + streamId + " len=" + payloadLength);
            }
        }

        this.accumulator.markReader();
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (this.accumulator != null) {
            this.accumulator.free();
            this.accumulator = null;
        }
    }
}
