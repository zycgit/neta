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
package net.hasor.neta.channel.quic;

import net.hasor.cobble.logging.Logger;

/**
 * Enforces inbound (receive-side) flow control at both the connection and stream level (RFC 9000 §4).
 * <p>
 * Tracks the total bytes received against the advertised flow control limits
 * ({@code MAX_DATA} for connection-level, {@code MAX_STREAM_DATA} for stream-level).
 * If the peer sends more data than allowed, the connection must be terminated with
 * a {@link QuicErrorCode#FLOW_CONTROL_ERROR}.
 * <p>
 * This class also provides auto-tuning: when consumption reaches a threshold percentage
 * of the current window, it generates a new {@code MAX_DATA} / {@code MAX_STREAM_DATA}
 * frame to advertise a larger limit.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicFlowControl {
    private static final Logger logger = Logger.getLogger(QuicFlowControl.class);

    /** When consumed exceeds this fraction of the current window, auto-expand. */
    private static final double AUTO_TUNE_THRESHOLD = 0.5;

    // ── Connection-level flow control ──────────────────────────────────
    /** Maximum data the peer is allowed to send at connection level (our receive limit). */
    private long connectionMaxData;
    /** Total bytes received at connection level. */
    private long connectionBytesReceived;

    // ── Per-stream flow control state is maintained externally (in QuicStreamChannel).
    // This class provides helper methods for validation.

    /**
     * Creates a flow control tracker with the initial connection-level limit.
     * @param initialMaxData the initial {@code MAX_DATA} value advertised to the peer
     */
    QuicFlowControl(long initialMaxData) {
        this.connectionMaxData = initialMaxData;
        this.connectionBytesReceived = 0;
    }

    /**
     * Builds a MAX_DATA frame (RFC 9000 §19.9) for the given limit.
     * @param maxData the new connection-level data limit
     * @return encoded MAX_DATA frame bytes
     */
    static byte[] buildMaxDataFrame(long maxData) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_DATA);
        byte[] valBytes = QuicVarInt.encode(maxData);
        byte[] frame = new byte[typeBytes.length + valBytes.length];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(valBytes, 0, frame, typeBytes.length, valBytes.length);
        return frame;
    }

    /**
     * Builds a MAX_STREAM_DATA frame (RFC 9000 §19.10).
     * @param streamId the stream ID
     * @param maxStreamData the new per-stream data limit
     * @return encoded MAX_STREAM_DATA frame bytes
     */
    static byte[] buildMaxStreamDataFrame(long streamId, long maxStreamData) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAM_DATA);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] valBytes = QuicVarInt.encode(maxStreamData);
        byte[] frame = new byte[typeBytes.length + sidBytes.length + valBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(valBytes, 0, frame, pos, valBytes.length);
        return frame;
    }

    /**
     * Records received data at the connection level.
     * Returns {@code true} if the data is within limits; {@code false} if the peer
     * has violated the flow control limit (and the connection should be closed).
     * @param bytes the number of bytes received
     * @return {@code true} if the data is within the connection-level {@code MAX_DATA} limit
     */
    synchronized boolean onConnectionDataReceived(long bytes) {
        this.connectionBytesReceived += bytes;
        if (this.connectionBytesReceived > this.connectionMaxData) {
            logger.error("Connection-level flow control violation: received=" + this.connectionBytesReceived + ", maxData=" + this.connectionMaxData);
            return false;
        }
        return true;
    }

    /**
     * Validates that the given stream offset + length does not exceed the stream's
     * per-stream flow control limit.
     * @param streamOffset the byte offset within the stream
     * @param length the number of data bytes
     * @param streamMaxData the current MAX_STREAM_DATA limit for this stream
     * @return {@code true} if within limits; {@code false} if violated
     */
    boolean validateStreamData(long streamOffset, long length, long streamMaxData) {
        long totalStreamBytes = streamOffset + length;
        if (totalStreamBytes > streamMaxData) {
            logger.error("Stream flow control violation: offset+length=" + totalStreamBytes + ", maxStreamData=" + streamMaxData);
            return false;
        }
        return true;
    }

    /**
     * Checks whether the connection-level flow control window should be expanded
     * (i.e., a MAX_DATA frame should be sent to the peer).
     * @return the new {@code MAX_DATA} value if expansion is needed, or {@code -1} if not necessary
     */
    synchronized long shouldExpandConnectionWindow() {
        double usageRatio = (double) this.connectionBytesReceived / this.connectionMaxData;
        if (usageRatio >= AUTO_TUNE_THRESHOLD) {
            // Double the window
            long newMaxData = this.connectionMaxData * 2;
            this.connectionMaxData = newMaxData;
            return newMaxData;
        }
        return -1;
    }

    /**
     * Checks whether a stream-level flow control window should be expanded.
     * @param streamBytesReceived total bytes received on the stream so far
     * @param streamMaxData current stream limit
     * @return the new MAX_STREAM_DATA value, or -1 if expansion is not needed
     */
    long shouldExpandStreamWindow(long streamBytesReceived, long streamMaxData) {
        if (streamMaxData <= 0) {
            return -1;
        }
        double usageRatio = (double) streamBytesReceived / streamMaxData;
        if (usageRatio >= AUTO_TUNE_THRESHOLD) {
            return streamMaxData * 2;
        }
        return -1;
    }

    /** Returns the current connection-level max data (our receive limit). */
    synchronized long getConnectionMaxData() {
        return this.connectionMaxData;
    }

    /** Returns the total bytes received at the connection level. */
    synchronized long getConnectionBytesReceived() {
        return this.connectionBytesReceived;
    }

    /** Updates the connection max data (e.g., from MAX_DATA sent by us). */
    synchronized void updateConnectionMaxData(long newMaxData) {
        this.connectionMaxData = Math.max(this.connectionMaxData, newMaxData);
    }
}
