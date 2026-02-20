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
package net.hasor.neta.codec.quic;

import java.util.HashMap;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * QUIC stream frame decoder that extracts stream data from QUIC packets and
 * reassembles them into a continuous byte stream per QUIC stream.
 * <p>
 * In the neta HTTP protocol stack, QUIC sits below HTTP/3. This decoder handles
 * the QUIC transport framing (RFC 9000) and produces {@link ByteBuf} output
 * for each QUIC stream. The HTTP/3 frame decoder then processes these buffers.
 * <p>
 * <b>Note:</b> This is a simplified QUIC codec that focuses on the framing aspects
 * relevant to HTTP/3, assuming the TLS/crypto layer has already been handled
 * by the transport. It processes STREAM, MAX_DATA, MAX_STREAM_DATA, RESET_STREAM,
 * and CONNECTION_CLOSE frames.
 * <p>
 * This decoder outputs: {@link ByteBuf} per-stream data for consumption by the
 * HTTP/3 frame decoder.
 */
public class QuicFrameDecoder implements ProtoHandler<ByteBuf, ByteBuf> {
    private final boolean               serverMode;
    private final Map<Long, QuicStream> streams;
    private final QuicSettings          localSettings;
    private final QuicSettings          remoteSettings;
    private       ByteBuf               accumulator;
    private       long                  connectionMaxData;
    private       long                  connectionDataReceived;

    /**
     * Creates a new QUIC frame decoder.
     * @param serverMode true for server-side, false for client-side
     */
    public QuicFrameDecoder(boolean serverMode) {
        this.serverMode = serverMode;
        this.streams = new HashMap<>();
        this.localSettings = new QuicSettings();
        this.remoteSettings = new QuicSettings();
        this.connectionMaxData = 0;
        this.connectionDataReceived = 0;
    }

    /** Returns the local QUIC settings. */
    public QuicSettings localSettings() {
        return localSettings;
    }

    /** Returns the remote QUIC settings. */
    public QuicSettings remoteSettings() {
        return remoteSettings;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        while (accumulator.readableBytes() > 0) {
            int startPos = accumulator.readableBytes();

            // Read the frame type (variable-length integer)
            byte[] peekBuf = new byte[Math.min(8, accumulator.readableBytes())];
            accumulator.getBytes(0, peekBuf, 0, peekBuf.length);

            long[] typeResult = QuicVarInt.decode(peekBuf, 0);
            int frameType = (int) typeResult[0];
            int typeLen = (int) typeResult[1];

            if (frameType == QuicFrameType.PADDING) {
                accumulator.skipReadableBytes(typeLen);
                continue;
            }

            if (frameType == QuicFrameType.PING) {
                accumulator.skipReadableBytes(typeLen);
                continue;
            }

            if (QuicFrameType.isStream(frameType)) {
                if (!processStreamFrame(context, dst, frameType, typeLen)) {
                    break; // Need more data
                }
            } else if (frameType == QuicFrameType.RESET_STREAM) {
                processResetStream(typeLen);
            } else if (frameType == QuicFrameType.MAX_DATA) {
                processMaxData(typeLen);
            } else if (frameType == QuicFrameType.MAX_STREAM_DATA) {
                processMaxStreamData(typeLen);
            } else if (frameType == QuicFrameType.CONNECTION_CLOSE || frameType == QuicFrameType.CONNECTION_CLOSE_APP) {
                processConnectionClose(typeLen);
                break;
            } else {
                // Skip unknown frames - read past the type byte(s)
                accumulator.skipReadableBytes(typeLen);
                // For unknown frames with length-prefixed payload, try to skip
                if (accumulator.readableBytes() > 0) {
                    byte[] lenBuf = new byte[Math.min(8, accumulator.readableBytes())];
                    accumulator.getBytes(0, lenBuf, 0, lenBuf.length);
                    try {
                        long[] lenResult = QuicVarInt.decode(lenBuf, 0);
                        int payloadLen = (int) lenResult[0];
                        int lenBytes = (int) lenResult[1];
                        if (accumulator.readableBytes() >= lenBytes + payloadLen) {
                            accumulator.skipReadableBytes(lenBytes + payloadLen);
                        }
                    } catch (Exception e) {
                        // Cannot determine length, stop processing
                        break;
                    }
                }
            }

            // Safety: if no progress was made, break to avoid infinite loop
            if (accumulator.readableBytes() == startPos) {
                break;
            }
        }

        accumulator.markReader();
        return ProtoStatus.Next;
    }

    /**
     * Processes a STREAM frame (type 0x08..0x0f).
     * <pre>
     *   STREAM Frame {
     *     Type (i) = 0x08..0x0f,
     *     Stream ID (i),
     *     [Offset (i)],     (if OFF bit set)
     *     [Length (i)],     (if LEN bit set)
     *     Stream Data (..),
     *   }
     * </pre>
     */
    private boolean processStreamFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int frameType, int typeLen) {
        boolean hasFin = QuicFrameType.streamFin(frameType);
        boolean hasLen = QuicFrameType.streamLen(frameType);
        boolean hasOff = QuicFrameType.streamOff(frameType);

        // We need to read the full header first
        int headerSize = typeLen;
        byte[] headerBuf = new byte[Math.min(32, accumulator.readableBytes())];
        accumulator.getBytes(0, headerBuf, 0, headerBuf.length);
        int pos = typeLen;

        // Stream ID
        long[] sidResult = QuicVarInt.decode(headerBuf, pos);
        long streamId = sidResult[0];
        pos += (int) sidResult[1];

        // Offset (optional)
        long offset = 0;
        if (hasOff) {
            long[] offResult = QuicVarInt.decode(headerBuf, pos);
            offset = offResult[0];
            pos += (int) offResult[1];
        }

        // Length (optional)
        int dataLength;
        if (hasLen) {
            long[] lenResult = QuicVarInt.decode(headerBuf, pos);
            dataLength = (int) lenResult[0];
            pos += (int) lenResult[1];
        } else {
            dataLength = accumulator.readableBytes() - pos;
        }

        // Check if we have enough data
        if (accumulator.readableBytes() < pos + dataLength) {
            return false; // Need more data
        }

        // Skip the header
        accumulator.skipReadableBytes(pos);

        // Read stream data
        byte[] streamData = new byte[dataLength];
        if (dataLength > 0) {
            accumulator.getBytes(0, streamData, 0, dataLength);
            accumulator.skipReadableBytes(dataLength);
        }

        // Track the stream
        QuicStream stream = streams.computeIfAbsent(streamId, sid -> {
            QuicStream s = new QuicStream(sid, localSettings.initialMaxStreamDataBidiLocal());
            s.state(QuicStreamState.OPEN);
            return s;
        });

        stream.advanceReceiveOffset(dataLength);
        connectionDataReceived += dataLength;

        // Emit the stream data as ByteBuf
        if (dataLength > 0 || hasFin) {
            ByteBuf buf = context.byteBufAllocator().buffer(Math.max(dataLength, 1));
            if (dataLength > 0) {
                buf.writeBytes(streamData, 0, dataLength);
            }
            // Encode stream ID in the first 8 bytes as metadata for the HTTP/3 layer
            ByteBuf output = context.byteBufAllocator().buffer(8 + dataLength + 1);
            // Header: streamId(8) + fin(1) + data
            byte[] streamIdBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                streamIdBytes[i] = (byte) (streamId & 0xFF);
                streamId >>>= 8;
            }
            output.writeBytes(streamIdBytes, 0, 8);
            output.writeBytes(new byte[] { (byte) (hasFin ? 1 : 0) }, 0, 1);
            if (dataLength > 0) {
                output.writeBytes(streamData, 0, dataLength);
            }
            output.markWriter();
            dst.offerMessage(output);
            buf.free();

            if (hasFin) {
                stream.state(QuicStreamState.HALF_CLOSED_REMOTE);
            }
        }

        return true;
    }

    /** Processes a RESET_STREAM frame. */
    private void processResetStream(int typeLen) {
        accumulator.skipReadableBytes(typeLen);

        byte[] buf = new byte[Math.min(24, accumulator.readableBytes())];
        accumulator.getBytes(0, buf, 0, buf.length);
        int pos = 0;

        long[] sidResult = QuicVarInt.decode(buf, pos);
        long streamId = sidResult[0];
        pos += (int) sidResult[1];

        long[] codeResult = QuicVarInt.decode(buf, pos);
        // long errorCode = codeResult[0]; // Application error code
        pos += (int) codeResult[1];

        long[] sizeResult = QuicVarInt.decode(buf, pos);
        // long finalSize = sizeResult[0];
        pos += (int) sizeResult[1];

        accumulator.skipReadableBytes(pos);

        QuicStream stream = streams.get(streamId);
        if (stream != null) {
            stream.state(QuicStreamState.RESET_REMOTE);
        }
    }

    /** Processes a MAX_DATA frame. */
    private void processMaxData(int typeLen) {
        accumulator.skipReadableBytes(typeLen);

        byte[] buf = new byte[Math.min(8, accumulator.readableBytes())];
        accumulator.getBytes(0, buf, 0, buf.length);

        long[] result = QuicVarInt.decode(buf, 0);
        connectionMaxData = result[0];
        accumulator.skipReadableBytes((int) result[1]);
    }

    /** Processes a MAX_STREAM_DATA frame. */
    private void processMaxStreamData(int typeLen) {
        accumulator.skipReadableBytes(typeLen);

        byte[] buf = new byte[Math.min(16, accumulator.readableBytes())];
        accumulator.getBytes(0, buf, 0, buf.length);
        int pos = 0;

        long[] sidResult = QuicVarInt.decode(buf, pos);
        long streamId = sidResult[0];
        pos += (int) sidResult[1];

        long[] maxResult = QuicVarInt.decode(buf, pos);
        long maxStreamData = maxResult[0];
        pos += (int) maxResult[1];

        accumulator.skipReadableBytes(pos);

        QuicStream stream = streams.get(streamId);
        if (stream != null) {
            stream.maxSendData(maxStreamData);
        }
    }

    /** Processes a CONNECTION_CLOSE frame. */
    private void processConnectionClose(int typeLen) {
        accumulator.skipReadableBytes(typeLen);

        byte[] buf = new byte[Math.min(24, accumulator.readableBytes())];
        accumulator.getBytes(0, buf, 0, buf.length);
        int pos = 0;

        long[] codeResult = QuicVarInt.decode(buf, pos);
        // long errorCode = codeResult[0];
        pos += (int) codeResult[1];

        long[] frameResult = QuicVarInt.decode(buf, pos);
        // long triggerFrameType = frameResult[0];
        pos += (int) frameResult[1];

        long[] lenResult = QuicVarInt.decode(buf, pos);
        int reasonLen = (int) lenResult[0];
        pos += (int) lenResult[1];

        // Skip reason phrase
        accumulator.skipReadableBytes(pos + reasonLen);

        // Close all streams
        for (QuicStream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }

    @Override
    public void onClose(ProtoContext context) {
        for (QuicStream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
        if (accumulator != null) {
            accumulator.free();
            accumulator = null;
        }
    }
}
