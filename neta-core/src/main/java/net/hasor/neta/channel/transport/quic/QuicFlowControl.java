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
package net.hasor.neta.channel.transport.quic;
import net.hasor.cobble.logging.Logger;

/**
 * Tracks the connection-level receive window and provides helper capabilities required for stream-level flow-control checks.
 * <p>This class maintains the connection-level byte counters required to process {@code MAX_DATA} and provides helper methods for validating stream offsets and building {@code MAX_DATA}/{@code MAX_STREAM_DATA} frames.
 * <p>Per-stream counters are not stored here; they are maintained by the stream objects and call these helper methods when needed.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicFlowControl {
    private static final Logger logger = Logger.getLogger(QuicFlowControl.class);

    /** Automatically expands when usage exceeds this ratio of the current window. */
    private static final double AUTO_TUNE_THRESHOLD = 0.5;

    // ── Connection-level flow control ─────────────────────────────────
    /** Maximum amount of data the peer is allowed to send at the connection level, that is, the local receive limit. */
    private long connectionMaxData;
    /** Total bytes received at the connection level. */
    private long connectionBytesReceived;

    // ── Per-stream flow-control state is maintained externally, for example by QuicStreamChannel.
    // This class only provides validation and frame-building helpers.

    /**
     * Creates a flow-control tracker using the given initial connection-level window.
     */
    QuicFlowControl(long initialMaxData) {
        this.connectionMaxData = initialMaxData;
        this.connectionBytesReceived = 0;
    }

    /**
     * Builds a MAX_DATA frame for the specified connection-level limit.
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
     * Builds a MAX_STREAM_DATA frame for the specified stream and limit value.
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
     * Records newly received bytes at the connection level.
     * @return returns false if the MAX_DATA limit is violated
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
     * Validates whether stream data exceeds the MAX_STREAM_DATA limit.
     * @return returns false if the limit is violated
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
     * Determines whether the connection-level window needs to be expanded.
     * @return returns the expanded value if needed, otherwise -1
     */
    synchronized long shouldExpandConnectionWindow() {
        double usageRatio = (double) this.connectionBytesReceived / this.connectionMaxData;
        if (usageRatio >= AUTO_TUNE_THRESHOLD) {
            // Expand the window to twice its current size.
            long newMaxData = this.connectionMaxData * 2;
            this.connectionMaxData = newMaxData;
            return newMaxData;
        }
        return -1;
    }

    /**
     * Determines whether the stream-level window needs to be expanded.
     * @return returns the expanded value if needed, otherwise -1
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

    /**
     * Returns the current maximum connection-level receive amount.
     */
    synchronized long getConnectionMaxData() {
        return this.connectionMaxData;
    }

    /**
     * Returns the total bytes currently received at the connection level.
     */
    synchronized long getConnectionBytesReceived() {
        return this.connectionBytesReceived;
    }

    /**
     * Updates the maximum connection-level data limit.
     */
    synchronized void updateConnectionMaxData(long newMaxData) {
        this.connectionMaxData = Math.max(this.connectionMaxData, newMaxData);
    }
}
