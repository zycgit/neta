/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.h3;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.quic.QuicStreamChannel;
import net.hasor.neta.channel.transport.quic.QuicVarInt;

/**
 * Encodes {@link Http3Frame} objects into HTTP/3 wire bytes.
 * <p>
 * Each frame is serialized as {@code varint type + varint length + payload}. When a frame carries
 * {@code fin=true} on a {@link QuicStreamChannel}, the encoder propagates QUIC FIN by closing the
 * current stream channel after the frame is written.
 * <p>
 * For non-QUIC channels such as virtual test channels, the encoder bundles all frames into a
 * single {@link ByteBuf} so they are delivered atomically.
 * <p>
 * Wire format (RFC 9114 Section 7.1):
 * <pre>
 * HTTP/3 Frame {
 * Type (i),
 * Length (i),
 * Frame Payload (..),
 * }
 * </pre>
 * @see Http3Frame
 * @see Http3HttpToFrameEncoder
 */
public class Http3FrameEncoder implements ProtoHandler<Http3Frame, ByteBuf> {
    private static final Logger logger    = Logger.getLogger(Http3FrameEncoder.class);
    /** Reusable varIntBuf scratch buffer to avoid per-frame allocations. */
    private final byte[]        varIntBuf = new byte[16];

    public Http3FrameEncoder() {
    }

    /**
     * Shared HTTP/3 settings initialization entry point.
     * <p>
     * The frame encoder itself does not consume settings directly, but keeps this constructor so
     * the full H3 codec chain can be initialized consistently.
     */
    public Http3FrameEncoder(Http3Settings localSettings) {
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http3Frame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        boolean isPrintLog = context.getConfig().isPrintLog();
        SoChannel<?> ch = context.getChannel();
        boolean isQuic = (ch instanceof QuicStreamChannel);
        boolean hasAny = false;

        if (!src.hasMore()) {
            return ProtoStatus.Next;
        }

        if (isQuic) {
            // QUIC mode emits one frame per write and uses channel close to propagate FIN.
            while (src.hasMore()) {
                if (!ByteBufUtils.hasWritableSlots(dst, 1)) {
                    return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
                }

                Http3Frame frame = src.takeMessage();
                if (frame == null) {
                    continue;
                }

                ByteBuf output = buildSingleFrame(context, frame, isPrintLog);
                dst.offerMessage(output);
                hasAny = true;
                if (frame.fin()) {
                    ch.close();
                }
            }
        } else {
            // Non-QUIC mode bundles all frames into a single ByteBuf.
            if (!ByteBufUtils.hasWritableSlots(dst, 1)) {
                return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
            }

            List<Http3Frame> frames = new ArrayList<>();
            for (Http3Frame frame : src.takeMessage(-1)) {
                if (frame != null) {
                    frames.add(frame);
                }
            }

            if (!frames.isEmpty()) {
                ByteBuf output = buildBundledFrames(context, frames, isPrintLog);
                dst.offerMessage(output);
                hasAny = true;
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Encodes a single HTTP/3 frame as an independent ByteBuf.
     * Serialization format: varint type + varint length + payload.
     */
    private ByteBuf buildSingleFrame(ProtoContext context, Http3Frame frame, boolean isPrintLog) {
        int typeLen = QuicVarInt.encodeTo(varIntBuf, 0, frame.type());
        int lenLen = QuicVarInt.encodeTo(varIntBuf, typeLen, frame.payloadLength());
        int frameHeaderLen = typeLen + lenLen;

        ByteBuf output = context.byteBufAllocator().buffer(frameHeaderLen + frame.payloadLength());
        output.writeBytes(varIntBuf, 0, frameHeaderLen);
        if (frame.payloadLength() > 0) {
            output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
        }
        output.markWriter();

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength());
        }
        return output;
    }

    /**
     * Bundles multiple HTTP/3 frames into one ByteBuf for non-QUIC channels.
     * This keeps test and virtual channels delivering the whole group atomically.
     */
    private ByteBuf buildBundledFrames(ProtoContext context, List<Http3Frame> frames, boolean isPrintLog) {
        int totalSize = 0;
        for (Http3Frame frame : frames) {
            totalSize += QuicVarInt.encodedLength(frame.type()) + QuicVarInt.encodedLength(frame.payloadLength()) + frame.payloadLength();
        }

        ByteBuf output = context.byteBufAllocator().buffer(totalSize);
        for (Http3Frame frame : frames) {
            int typeLen = QuicVarInt.encodeTo(varIntBuf, 0, frame.type());
            int lenLen = QuicVarInt.encodeTo(varIntBuf, typeLen, frame.payloadLength());
            output.writeBytes(varIntBuf, 0, typeLen + lenLen);
            if (frame.payloadLength() > 0) {
                output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
            }

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength() + " (bundled)");
            }
        }
        output.markWriter();
        return output;
    }
}
