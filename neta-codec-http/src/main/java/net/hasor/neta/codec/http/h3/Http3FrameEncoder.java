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
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.quic.QuicStreamChannel;
import net.hasor.neta.channel.quic.QuicVarInt;

/**
 * HTTP/3 binary frame encoder that converts {@link Http3Frame} objects into
 * raw bytes ({@code ByteBuf}).
 * <p>
 * This encoder serializes each {@link Http3Frame} using the HTTP/3 wire format:
 * varint type + varint length + payload. It also handles QUIC FIN signaling
 * by closing the stream channel when a frame with {@code fin=true} is written.
 * <p>
 * For non-QUIC channels (e.g. VrtChannel, testing), all frames are bundled
 * into a single ByteBuf to ensure atomic delivery.
 * <p>
 * <b>Encode path:</b> {@code HttpObject → Http3Frame → ByteBuf}
 * <p>
 * Frame format (RFC 9114, Section 7.1):
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),       — QUIC variable-length integer
 *     Length (i),     — QUIC variable-length integer
 *     Frame Payload (..),
 *   }
 * </pre>
 * @see Http3Frame
 * @see Http3HttpToFrameEncoder
 */
public class Http3FrameEncoder implements ProtoHandler<Http3Frame, ByteBuf> {
    private static final Logger logger    = Logger.getLogger(Http3FrameEncoder.class);
    /** Reusable buffer for varint encoding to avoid per-frame allocation. */
    private final        byte[] varintBuf = new byte[16];

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http3Frame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        if (!src.hasMore()) {
            return ProtoStatus.Next;
        }

        boolean isPrintLog = context.getConfig() != null && context.getConfig().isPrintLog();
        SoChannel<?> ch = context.getChannel();
        boolean isQuic = (ch instanceof QuicStreamChannel);

        if (isQuic) {
            // QUIC mode: write each frame separately, signal FIN via channel close
            while (src.hasMore()) {
                Http3Frame frame = src.takeMessage();
                if (frame == null) {
                    continue;
                }
                writeFrame(context, dst, frame, isPrintLog);
                if (frame.fin()) {
                    ch.close();
                }
            }
        } else {
            // Non-QUIC mode: bundle all frames into a single ByteBuf
            List<Http3Frame> frames = new ArrayList<>();
            while (src.hasMore()) {
                Http3Frame frame = src.takeMessage();
                if (frame != null) {
                    frames.add(frame);
                }
            }
            if (!frames.isEmpty()) {
                writeBundledFrames(context, dst, frames, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Writes a single HTTP/3 frame to the output.
     * Serializes: varint type + varint length + payload.
     */
    private void writeFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst, Http3Frame frame, boolean isPrintLog) {
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, frame.type());
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, frame.payloadLength());
        int frameHeaderLen = typeLen + lenLen;

        ByteBuf output = context.byteBufAllocator().buffer(frameHeaderLen + frame.payloadLength());
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        if (frame.payloadLength() > 0) {
            output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
        }
        output.markWriter();
        dst.offerMessage(output);

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength());
        }
    }

    /**
     * Bundles multiple HTTP/3 frames into a single ByteBuf for non-QUIC channels.
     * This ensures VrtTransfer delivers all frames as one unit, allowing the decoder
     * to process them together with a single fin=true.
     */
    private void writeBundledFrames(ProtoContext context, ProtoSndQueue<ByteBuf> dst, List<Http3Frame> frames, boolean isPrintLog) {
        // Calculate total size
        int totalSize = 0;
        for (Http3Frame frame : frames) {
            totalSize += QuicVarInt.encodedLength(frame.type()) + QuicVarInt.encodedLength(frame.payloadLength()) + frame.payloadLength();
        }

        ByteBuf output = context.byteBufAllocator().buffer(totalSize);
        for (Http3Frame frame : frames) {
            int typeLen = QuicVarInt.encodeTo(varintBuf, 0, frame.type());
            int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, frame.payloadLength());
            output.writeBytes(varintBuf, 0, typeLen + lenLen);
            if (frame.payloadLength() > 0) {
                output.writeBytes(frame.payload(), frame.payloadOffset(), frame.payloadLength());
            }

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-SND-FRAME] ch=" + channelID + " " + Http3FrameType.name(frame.type()) + " stream=" + frame.streamId() + " fin=" + frame.fin() + " len=" + frame.payloadLength() + " (bundled)");
            }
        }
        output.markWriter();
        dst.offerMessage(output);
    }

    @Override
    public void onClose(ProtoContext context) {
        // No resources to clean up
    }
}
