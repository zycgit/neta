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
 * Enforces receive-side flow control at connection and stream level per RFC 9000 §4, with auto-tuning window expansion.
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

    /** Creates a flow control tracker with the given initial connection-level max data. */
    QuicFlowControl(long initialMaxData) {
        this.connectionMaxData = initialMaxData;
        this.connectionBytesReceived = 0;
    }

    /** Builds a MAX_DATA frame (RFC 9000 §19.9) for the given connection-level limit. */
    static byte[] buildMaxDataFrame(long maxData) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_DATA);
        byte[] valBytes = QuicVarInt.encode(maxData);
        byte[] frame = new byte[typeBytes.length + valBytes.length];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(valBytes, 0, frame, typeBytes.length, valBytes.length);
        return frame;
    }

    /** Builds a MAX_STREAM_DATA frame (RFC 9000 §19.10) for the given stream and limit. */
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

    /** Records received bytes at connection level; returns false if the MAX_DATA limit is violated. */
    synchronized boolean onConnectionDataReceived(long bytes) {
        this.connectionBytesReceived += bytes;
        if (this.connectionBytesReceived > this.connectionMaxData) {
            logger.error("Connection-level flow control violation: received=" + this.connectionBytesReceived + ", maxData=" + this.connectionMaxData);
            return false;
        }
        return true;
    }

    /** Validates stream data does not exceed MAX_STREAM_DATA; returns false on violation. */
    boolean validateStreamData(long streamOffset, long length, long streamMaxData) {
        long totalStreamBytes = streamOffset + length;
        if (totalStreamBytes > streamMaxData) {
            logger.error("Stream flow control violation: offset+length=" + totalStreamBytes + ", maxStreamData=" + streamMaxData);
            return false;
        }
        return true;
    }

    /** Returns the new doubled MAX_DATA if usage exceeds the auto-tune threshold, or -1 if not needed. */
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

    /** Returns the new doubled MAX_STREAM_DATA if usage exceeds the auto-tune threshold, or -1 if not needed. */
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
