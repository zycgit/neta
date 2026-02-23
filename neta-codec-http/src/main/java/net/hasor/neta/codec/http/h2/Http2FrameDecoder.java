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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpProtocolViolationException;

/**
 * HTTP/2 binary frame decoder that converts raw bytes into {@link Http2Frame} objects.
 * <p>
 * This decoder implements the HTTP/2 binary framing layer defined in RFC 9113.
 * It handles the connection preface, parses the 9-byte frame header, extracts
 * the payload, and emits {@link Http2Frame} instances for downstream semantic
 * processing by {@link Http2FrameToHttpDecoder}.
 * <p>
 * <b>Decode path:</b> {@code ByteBuf → Http2Frame → HttpObject}
 * <p>
 * Frame format (RFC 9113, Section 4):
 * <pre>
 *   +-----------------------------------------------+
 *   |                 Length (24)                     |
 *   +---------------+---------------+---------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+--------------+
 *   |R|                 Stream Identifier (31)       |
 *   +=+==============================================+
 *   |                 Frame Payload (0...)            |
 *   +------------------------------------------------+
 * </pre>
 * @see Http2Frame
 * @see Http2FrameToHttpDecoder
 */
public class Http2FrameDecoder implements ProtoHandler<ByteBuf, Http2Frame> {
    /** HTTP/2 connection preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" */
    private static final byte[] CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    /** Frame header size: 9 bytes */
    private static final int    FRAME_HEADER_SIZE  = 9;

    private final boolean       serverMode;
    private final Http2Settings localSettings;

    private ByteBuf accumulator;
    private boolean prefaceReceived;

    /** Reusable frame header buffer to avoid per-frame byte[9] allocation. */
    private final byte[] frameHeaderBuf  = new byte[FRAME_HEADER_SIZE];
    /** Reusable preface buffer. */
    private final byte[] prefaceCheckBuf = new byte[CONNECTION_PREFACE.length];

    /**
     * Creates a new HTTP/2 frame decoder.
     * @param serverMode true for server-side (expects client preface), false for client-side
     */
    public Http2FrameDecoder(boolean serverMode) {
        this.serverMode = serverMode;
        this.localSettings = new Http2Settings();
        this.prefaceReceived = !serverMode; // Client doesn't receive a preface
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        // Handle connection preface for server mode
        if (!prefaceReceived) {
            if (accumulator.readableBytes() < CONNECTION_PREFACE.length) {
                accumulator.markReader();
                return ProtoStatus.Next;
            }
            byte[] prefaceBytes = this.prefaceCheckBuf;
            accumulator.getBytes(0, prefaceBytes, 0, prefaceBytes.length);
            for (int i = 0; i < CONNECTION_PREFACE.length; i++) {
                if (prefaceBytes[i] != CONNECTION_PREFACE[i]) {
                    throw new HttpProtocolViolationException("HTTP/2: invalid connection preface");
                }
            }
            accumulator.skipReadableBytes(CONNECTION_PREFACE.length);
            prefaceReceived = true;
        }

        // Decode frames
        while (accumulator.readableBytes() >= FRAME_HEADER_SIZE) {
            // Peek at header without consuming
            byte[] headerBuf = this.frameHeaderBuf;
            accumulator.getBytes(0, headerBuf, 0, FRAME_HEADER_SIZE);

            int payloadLength = ((headerBuf[0] & 0xFF) << 16) | ((headerBuf[1] & 0xFF) << 8) | (headerBuf[2] & 0xFF);
            int type = headerBuf[3] & 0xFF;
            int flags = headerBuf[4] & 0xFF;
            int streamId = ((headerBuf[5] & 0x7F) << 24) | ((headerBuf[6] & 0xFF) << 16) | ((headerBuf[7] & 0xFF) << 8) | (headerBuf[8] & 0xFF);

            // Validate frame size
            if (payloadLength > localSettings.maxFrameSize()) {
                throw new HttpProtocolViolationException("HTTP/2: frame size exceeds SETTINGS_MAX_FRAME_SIZE: " + payloadLength);
            }

            int totalFrameSize = FRAME_HEADER_SIZE + payloadLength;
            if (accumulator.readableBytes() < totalFrameSize) {
                // Not enough data for the full frame; wait for more
                break;
            }

            // Skip frame header
            accumulator.skipReadableBytes(FRAME_HEADER_SIZE);

            // Read payload
            byte[] payload = new byte[payloadLength];
            if (payloadLength > 0) {
                accumulator.getBytes(0, payload, 0, payloadLength);
                accumulator.skipReadableBytes(payloadLength);
            }

            // Emit Http2Frame
            dst.offerMessage(new Http2Frame(type, flags, streamId, payload));
        }

        accumulator.markReader();
        return ProtoStatus.Next;
    }

    /** Returns true if this is server mode. */
    boolean isServerMode() {
        return this.serverMode;
    }

    /** Returns true if the HTTP/2 connection preface has been received. */
    boolean isPrefaceReceived() {
        return this.prefaceReceived;
    }

    /** Returns the local HTTP/2 settings (for frame size validation). */
    Http2Settings localSettings() {
        return this.localSettings;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (accumulator != null) {
            accumulator.free();
            accumulator = null;
        }
    }
}
