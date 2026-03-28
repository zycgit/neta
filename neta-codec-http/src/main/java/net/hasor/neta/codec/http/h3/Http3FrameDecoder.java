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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.quic.QuicStreamChannel;
import net.hasor.neta.channel.quic.QuicVarInt;

/**
 * HTTP/3 binary frame decoder that converts raw bytes ({@code ByteBuf}) into
 * {@link Http3Frame} objects.
 * <p>
 * This decoder implements the HTTP/3 binary framing layer defined in RFC 9114.
 * It extracts QUIC stream metadata (stream ID, FIN flag), parses the
 * variable-length integer frame type and length, reads the payload, and emits
 * {@link Http3Frame} instances for downstream semantic processing by
 * {@link Http3FrameToHttpDecoder}.
 * <p>
 * For unidirectional streams, the stream type (first varint) is parsed and
 * tracked per-stream to avoid re-reading on subsequent data chunks.
 * <p>
 * <b>Decode path:</b> {@code ByteBuf → Http3Frame → HttpObject}
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
 * @see Http3FrameToHttpDecoder
 */
public class Http3FrameDecoder implements ProtoHandler<ByteBuf, Http3Frame> {
    private static final Logger logger = Logger.getLogger(Http3FrameDecoder.class);

    private final boolean         serverMode;
    /** Fallback metadata queue for non-QUIC testing (streamId + fin). */
    private final Queue<long[]>   fallbackMeta           = new LinkedList<>();
    /** Tracks unidirectional stream types to avoid re-reading on subsequent chunks. */
    private final Map<Long, Long> uniStreamTypes         = new HashMap<>();
    private       long            nonQuicStreamIdCounter = 0;

    /**
     * Creates a new HTTP/3 binary frame decoder.
     * @param serverMode true for server-side (expects requests), false for client-side
     */
    public Http3FrameDecoder(boolean serverMode) {
        this.serverMode = serverMode;
    }

    /**
     * Pre-loads stream metadata for the next incoming message (for non-QUIC usage).
     * <p>
     * When no {@link QuicStreamChannel} is available (e.g. in codec unit tests or
     * over a VirtualChannel), this method can be used to supply per-message
     * stream metadata that would normally come from the QUIC transport.
     * @param streamId the QUIC stream ID
     * @param fin true if this is the final data on the stream
     */
    public void pushFallbackMeta(long streamId, boolean fin) {
        fallbackMeta.offer(new long[] { streamId, fin ? 1 : 0 });
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Http3Frame> dst) throws Throwable {
        SoChannel<?> ch = context.getChannel();
        QuicStreamChannel streamChannel = (ch instanceof QuicStreamChannel) ? (QuicStreamChannel) ch : null;
        boolean isPrintLog = context.getConfig().isPrintLog();

        while (src.hasMore()) {
            ByteBuf msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            // Extract stream metadata
            long streamId;
            // Empty ByteBuf is the FIN signal delivered by QUIC after reassembly completes.
            boolean fin = msg.readableBytes() == 0;
            if (streamChannel != null) {
                streamId = streamChannel.getStreamId();
            } else {
                long[] meta = fallbackMeta.poll();
                if (meta != null) {
                    streamId = meta[0];
                    fin = meta[1] != 0;
                } else {
                    streamId = nonQuicStreamIdCounter;
                    nonQuicStreamIdCounter += 4;
                    fin = true;
                }
            }

            int dataLen = msg.readableBytes();
            // Empty ByteBuf is the FIN signal delivered by QUIC after reassembly completes.
            if (dataLen == 0) {
                if (fin) {
                    parseFrames(context, dst, streamId, new byte[0], 0, 0, true, isPrintLog);
                }
                continue;
            }
            byte[] data = new byte[dataLen];
            if (dataLen > 0) {
                msg.getBytes(0, data, 0, dataLen);
            }

            // Check if this is a unidirectional stream (bit 1 set in stream ID)
            if ((streamId & 0x02) != 0) {
                parseUnidirectionalStream(context, dst, streamId, data, 0, dataLen, isPrintLog);
            } else {
                parseFrames(context, dst, streamId, data, 0, dataLen, fin, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Parses HTTP/3 frames from raw data and emits {@link Http3Frame} objects.
     * <p>
     * Each frame consists of a varint type, varint length, and payload.
     * The FIN flag is set only on the last frame in the data chunk.
     */
    private void parseFrames(ProtoContext context, ProtoSndQueue<Http3Frame> dst, long streamId, byte[] data, int offset, int length, boolean fin, boolean isPrintLog) {
        int pos = offset;
        int end = offset + length;

        while (pos < end) {
            // Read frame type (variable-length int)
            long[] typeResult = QuicVarInt.decode(data, pos);
            long frameType = typeResult[0];
            pos += (int) typeResult[1];

            // Read frame length (variable-length int)
            long[] lenResult = QuicVarInt.decode(data, pos);
            int frameLength = (int) lenResult[0];
            pos += (int) lenResult[1];

            if (pos + frameLength > end) {
                break; // Incomplete frame - wait for more data
            }

            // Determine if this is the last frame in the chunk
            boolean lastFrame = (pos + frameLength >= end) && fin;

            // Extract payload
            byte[] payload = new byte[frameLength];
            if (frameLength > 0) {
                System.arraycopy(data, pos, payload, 0, frameLength);
            }

            dst.offerMessage(new Http3Frame(frameType, streamId, lastFrame, payload));

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV-FRAME] ch=" + channelID + " " + Http3FrameType.name(frameType) + " stream=" + streamId + " fin=" + lastFrame + " len=" + frameLength);
            }

            pos += frameLength;
        }
    }

    /**
     * Parses data on a unidirectional stream.
     * <p>
     * On the first data chunk for a stream, the stream type varint is read
     * and tracked. Subsequent chunks skip the stream type and parse frames
     * directly, fixing the re-read bug in the original implementation.
     */
    private void parseUnidirectionalStream(ProtoContext context, ProtoSndQueue<Http3Frame> dst, long streamId, byte[] data, int offset, int length, boolean isPrintLog) {
        if (length == 0) {
            return;
        }

        int pos = offset;
        int end = offset + length;

        // Check if we already know this stream's type
        Long knownType = uniStreamTypes.get(streamId);
        if (knownType == null) {
            // First data on this unidirectional stream - read stream type varint
            long[] typeResult = QuicVarInt.decode(data, pos);
            long streamType = typeResult[0];
            pos += (int) typeResult[1];
            uniStreamTypes.put(streamId, streamType);

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV-FRAME] ch=" + channelID + " UNI-STREAM type=" + streamType + " stream=" + streamId);
            }
        }

        // Parse frames on this unidirectional stream (control stream uses same frame format)
        if (pos < end) {
            parseFrames(context, dst, streamId, data, pos, end - pos, false, isPrintLog);
        }
    }

    /** Returns true if this is server mode. */
    boolean isServerMode() {
        return this.serverMode;
    }

    @Override
    public void onClose(ProtoContext context) {
        fallbackMeta.clear();
        uniStreamTypes.clear();
    }
}
