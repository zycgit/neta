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

/**
 * HTTP/2 binary frame encoder that converts {@link Http2Frame} objects into
 * raw bytes ({@code ByteBuf}).
 * <p>
 * This encoder serializes the 9-byte frame header and payload for each
 * {@link Http2Frame}, producing the HTTP/2 wire format defined in RFC 9113.
 * <p>
 * <b>Encode path:</b> {@code HttpObject → Http2Frame → ByteBuf}
 * <p>
 * Special handling: frames with type {@link Http2FrameType#PREFACE} are written
 * as raw bytes (the client connection preface) without a frame header.
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
 * @see Http2ObjectEncoder
 */
public class Http2FrameEncoder implements ProtoHandler<Http2Frame, ByteBuf> {
    private static final Logger logger            = Logger.getLogger(Http2FrameEncoder.class);
    private static final int    FRAME_HEADER_SIZE = 9;

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        boolean isPrintLog = context.getConfig().isPrintLog();
        long channelID = context.getChannel().getChannelId();

        while (src.hasMore()) {
            Http2Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }
            dst.offerMessage(encodeFrame(context, frame));
            if (isPrintLog) {
                if (frame.type() == Http2FrameType.PREFACE) {
                    logger.info("[H2-SND-FRAME] ch=" + channelID + " PREFACE len=" + frame.payloadLength());
                } else {
                    logger.info("[H2-SND-FRAME] ch=" + channelID + " " + Http2FrameType.name(frame.type()) +//
                            " flags=" + Http2Flags.describe(frame.type(), frame.flags()) +//
                            " stream=" + frame.streamId() + //
                            " len=" + frame.payloadLength());
                }
            }
        }

        return ProtoStatus.Next;
    }

    private static ByteBuf encodeFrame(ProtoContext context, Http2Frame frame) {
        if (frame.type() == Http2FrameType.PREFACE) {
            byte[] payload = frame.payload();
            int offset = frame.payloadOffset();
            int length = frame.payloadLength();
            ByteBuf buf = context.byteBufAllocator().buffer(length);
            buf.writeBytes(payload, offset, length);
            buf.markWriter();
            return buf;
        }
        return buildFrameBuffer(context, frame);
    }

    /**
     * Writes a single HTTP/2 frame to the output.
     * Writes the 9-byte frame header followed by the payload.
     */
    private static ByteBuf buildFrameBuffer(ProtoContext context, Http2Frame frame) {
        int payloadLength = frame.payloadLength();
        ByteBuf buf = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLength);

        // Write frame header (9 bytes)
        buf.writeInt24(payloadLength);
        buf.writeByte((byte) frame.type());
        buf.writeByte((byte) frame.flags());
        buf.writeInt32(frame.streamId() & 0x7FFFFFFF);

        // Write payload
        if (payloadLength > 0) {
            buf.writeBytes(frame.payload(), frame.payloadOffset(), payloadLength);
        }

        buf.markWriter();
        return buf;
    }
}
