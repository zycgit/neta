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

import java.util.Map;
import java.util.TreeMap;
import net.hasor.cobble.logging.Logger;

/**
 * Reassembles out-of-order STREAM and CRYPTO frame data into a contiguous byte sequence
 * (RFC 9000 §2.2 for STREAM, §19.6 for CRYPTO).
 * <p>
 * QUIC allows stream data to arrive out of order. This class buffers fragments
 * indexed by byte offset and delivers them in order once the next expected offset
 * is available.
 * <p>
 * <b>Usage pattern</b>:
 * <pre>
 *   reassembler.addFragment(offset, data);
 *   byte[] contiguous = reassembler.readContiguous();
 *   if (contiguous != null) { // deliver to application }
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicStreamReassembler {
    private static final Logger logger = Logger.getLogger(QuicStreamReassembler.class);

    /** Buffered out-of-order fragments: offset → data. Sorted by offset. */
    private final TreeMap<Long, byte[]> fragments           = new TreeMap<>();
    /** The next expected byte offset for in-order delivery. */
    private       long                  nextExpectedOffset  = 0;
    /** Total bytes delivered so far (= nextExpectedOffset). */
    private       long                  totalBytesDelivered = 0;
    /** Maximum buffer size to prevent unbounded memory growth (default 4MB). */
    private       long                  maxBufferSize       = 4 * 1024 * 1024;
    /** Current buffered data size in bytes. */
    private       long                  currentBufferSize   = 0;
    /** Whether a FIN has been received. */
    private       boolean               finReceived         = false;
    /** The final byte offset (set when FIN is received). */
    private       long                  finalOffset         = -1;

    /** Sets the maximum buffer size for out-of-order data. */
    void setMaxBufferSize(long maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Adds a fragment of data at the given offset.
     * @param offset the byte offset within the stream/crypto context
     * @param data the fragment data
     * @param fin whether this fragment carries the FIN flag (STREAM only)
     * @return {@code true} if the fragment was accepted; {@code false} if it was rejected
     * (e.g., buffer overflow, duplicate, or invalid offset)
     */
    synchronized boolean addFragment(long offset, byte[] data, boolean fin) {
        if (data == null || data.length == 0) {
            // FIN-only frame
            if (fin) {
                this.finReceived = true;
                this.finalOffset = offset;
            }
            return true;
        }

        // Skip data already delivered
        if (offset + data.length <= this.nextExpectedOffset) {
            // Entirely duplicate — skip
            if (fin) {
                this.finReceived = true;
                this.finalOffset = offset + data.length;
            }
            return true;
        }

        // Trim partial overlap with already-delivered data
        if (offset < this.nextExpectedOffset) {
            int skip = (int) (this.nextExpectedOffset - offset);
            byte[] trimmed = new byte[data.length - skip];
            System.arraycopy(data, skip, trimmed, 0, trimmed.length);
            offset = this.nextExpectedOffset;
            data = trimmed;
        }

        // Buffer size check
        if (this.currentBufferSize + data.length > this.maxBufferSize) {
            logger.error("Stream reassembly buffer overflow: buffered=" + this.currentBufferSize + ", incoming=" + data.length + ", max=" + this.maxBufferSize);
            return false;
        }

        // Handle overlapping with existing fragments
        // Simple approach: just insert (last write wins for overlapping regions)
        this.fragments.put(offset, data);
        this.currentBufferSize += data.length;

        if (fin) {
            this.finReceived = true;
            this.finalOffset = offset + data.length;
        }
        return true;
    }

    /**
     * Reads and removes all contiguous data starting from {@link #nextExpectedOffset}.
     * @return the contiguous data bytes, or {@code null} if no contiguous data is available
     */
    synchronized byte[] readContiguous() {
        if (this.fragments.isEmpty()) {
            return null;
        }

        // Check if the next expected offset is available
        Map.Entry<Long, byte[]> first = this.fragments.firstEntry();
        if (first == null || first.getKey() > this.nextExpectedOffset) {
            return null; // gap — can't deliver yet
        }

        // Collect contiguous fragments
        int totalLen = 0;
        java.util.List<Map.Entry<Long, byte[]>> contiguous = new java.util.ArrayList<>();
        long expected = this.nextExpectedOffset;

        while (true) {
            Map.Entry<Long, byte[]> entry = this.fragments.firstEntry();
            if (entry == null) {
                break;
            }
            long fragOffset = entry.getKey();
            byte[] fragData = entry.getValue();

            if (fragOffset > expected) {
                break; // gap found
            }

            // Fragment starts at or before expected offset
            if (fragOffset + fragData.length > expected) {
                // Fragment contributes new bytes
                contiguous.add(entry);
                totalLen += (int) (fragOffset + fragData.length - expected);
                expected = fragOffset + fragData.length;
            }
            this.fragments.pollFirstEntry();
            this.currentBufferSize -= fragData.length;
        }

        if (totalLen == 0) {
            return null;
        }

        // Assemble contiguous data
        byte[] result = new byte[totalLen];
        int pos = 0;
        long deliverOffset = this.nextExpectedOffset;
        for (Map.Entry<Long, byte[]> entry : contiguous) {
            long fragOffset = entry.getKey();
            byte[] fragData = entry.getValue();
            int skip = 0;
            if (fragOffset < deliverOffset) {
                skip = (int) (deliverOffset - fragOffset);
            }
            int copyLen = fragData.length - skip;
            if (copyLen > 0) {
                System.arraycopy(fragData, skip, result, pos, copyLen);
                pos += copyLen;
                deliverOffset = fragOffset + fragData.length;
            }
        }

        this.nextExpectedOffset = deliverOffset;
        this.totalBytesDelivered = deliverOffset;
        return result;
    }

    /** Returns the next expected offset (i.e., total bytes delivered so far). */
    synchronized long getNextExpectedOffset() {
        return this.nextExpectedOffset;
    }

    /** Returns the total bytes delivered to the application. */
    synchronized long getTotalBytesDelivered() {
        return this.totalBytesDelivered;
    }

    /** Returns {@code true} if FIN has been received. */
    synchronized boolean isFinReceived() {
        return this.finReceived;
    }

    /** Returns {@code true} if all data up to FIN has been delivered. */
    synchronized boolean isComplete() {
        return this.finReceived && this.nextExpectedOffset >= this.finalOffset && this.fragments.isEmpty();
    }

    /** Returns the current number of buffered bytes. */
    synchronized long getBufferedSize() {
        return this.currentBufferSize;
    }

    /** Returns {@code true} if there are buffered out-of-order fragments. */
    synchronized boolean hasBufferedFragments() {
        return !this.fragments.isEmpty();
    }
}
