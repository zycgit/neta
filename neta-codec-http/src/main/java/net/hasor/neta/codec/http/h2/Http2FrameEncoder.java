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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Encodes HTTP/2 binary frames into outbound socket bytes.
 * <p>
 * This encoder serializes {@link Http2Frame} objects into the wire format defined by RFC 9113. It
 * typically appears at the end of the HTTP/2 outbound pipeline, receives frames produced by the
 * upstream {@link Http2ObjectEncoder}, writes the 9-byte frame header and payload, and hands the
 * result to the transport layer.
 * <p>
 * A single connection typically arrives at the encoder as an ordered frame stream:
 * <pre>
 *   [PREFACE]? -> [SETTINGS] -> [HEADERS] -> [DATA]* -> [WINDOW_UPDATE]* -> [GOAWAY] ...
 * </pre>
 * Ordinary frames are written as a standard frame header plus payload. The special
 * {@link Http2FrameType#PREFACE} frame writes the raw client connection preface bytes directly and
 * does not include a frame header.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("h2-object", new Http2ObjectEncoder(false));
 *   ctx.addLastEncoder("h2-frame", new Http2FrameEncoder());
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   Http2Frame
 *      -> Http2FrameEncoder
 *      -> socket bytes
 * </pre>
 * <p>
 * Frame format (RFC 9113 Section 4):
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
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-24
 */
public class Http2FrameEncoder implements ProtoHandler<Http2Frame, ByteBuf> {
    private static final Logger logger            = Logger.getLogger(Http2FrameEncoder.class);
    private static final int    FRAME_HEADER_SIZE = 9;

    /**
     * Encodes outbound HTTP/2 frames into {@link ByteBuf} instances.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        boolean isPrintLog = context.getConfig().isPrintLog();
        long channelID = context.getChannel().getChannelId();
        boolean hasAny = false;

        while (src.hasMore()) {
            if (!ByteBufUtils.hasWritableSlots(dst, 1)) {
                return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
            }

            Http2Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            ByteBuf output = encodeFrame(context, frame);
            dst.offerMessage(output);
            hasAny = true;
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
     * Writes a single HTTP/2 frame to the output buffer.
     * The 9-byte frame header is written first, followed by the payload.
     */
    private static ByteBuf buildFrameBuffer(ProtoContext context, Http2Frame frame) {
        int payloadLength = frame.payloadLength();
        ByteBuf buf = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLength);

        // Write the 9-byte frame header.
        buf.writeInt24(payloadLength);
        buf.writeByte((byte) frame.type());
        buf.writeByte((byte) frame.flags());
        buf.writeInt32(Http2Frame.requireWireStreamId(frame.streamId()));

        // Write the payload.
        if (payloadLength > 0) {
            buf.writeBytes(frame.payload(), frame.payloadOffset(), payloadLength);
        }

        buf.markWriter();
        return buf;
    }
}
