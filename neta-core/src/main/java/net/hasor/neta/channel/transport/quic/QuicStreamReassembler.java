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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.hasor.cobble.logging.Logger;
/**
 * Reassembles out-of-order STREAM or CRYPTO fragments into a contiguous byte sequence.
 * <p>
 * QUIC frame payloads can arrive with offsets, may overlap due to retransmission, and do not have to complete in
 * order. This helper buffers fragments by offset and only releases data upward when a contiguous prefix exists from
 * the current read cursor.
 * <pre>
 *   receive:  offset 6 -> [ghi]
 *             offset 0 -> [abcdef]
 *   buffered: [0..5] [6..8]
 *   deliver : [abcdefghi]
 *   next    : offset 9
 * </pre>
 * <p>
 * It is used for both stream data and CRYPTO data, so the logic only focuses on ordered byte reconstruction and does
 * not interpret specific frame semantics directly.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicStreamReassembler {
    private static final Logger logger = Logger.getLogger(QuicStreamReassembler.class);

    /** Out-of-order fragments buffered by offset, structured as offset → data and sorted by offset. */
    private final TreeMap<Long, byte[]> fragments           = new TreeMap<>();
    /** Byte offset expected next for in-order delivery. */
    private long                        nextExpectedOffset  = 0;
    /** Total number of bytes already delivered upstream, equal to nextExpectedOffset. */
    private long                        totalBytesDelivered = 0;
    /** Maximum buffer size used to prevent unbounded memory growth; defaults to 4 MB. */
    private long                        maxBufferSize       = 4 * 1024 * 1024;
    /** Total number of bytes currently buffered. */
    private long                        currentBufferSize   = 0;
    /** Whether FIN has already been received. */
    private boolean                     finReceived         = false;
    /** Final byte offset, determined when FIN is received. */
    private long                        finalOffset         = -1;

    /**
     * Sets the maximum buffer size for out-of-order data.
     */
    void setMaxBufferSize(long maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Adds a data fragment at the given offset; returns false if the buffer limit is exceeded or the offset is invalid.
     */
    synchronized boolean addFragment(long offset, byte[] data, boolean fin) {
        if (data == null || data.length == 0) {
            // FIN-only frame.
            if (fin) {
                this.finReceived = true;
                this.finalOffset = offset;
            }
            return true;
        }

        // Skip data that has already been delivered.
        if (offset + data.length <= this.nextExpectedOffset) {
            // Fully duplicated data, skip it directly.
            if (fin) {
                this.finReceived = true;
                this.finalOffset = offset + data.length;
            }
            return true;
        }

        // Trim the part overlapping with already delivered data.
        if (offset < this.nextExpectedOffset) {
            int skip = (int) (this.nextExpectedOffset - offset);
            byte[] trimmed = new byte[data.length - skip];
            System.arraycopy(data, skip, trimmed, 0, trimmed.length);
            offset = this.nextExpectedOffset;
            data = trimmed;
        }

        // Check buffer size.
        if (this.currentBufferSize + data.length > this.maxBufferSize) {
            logger.error("Stream reassembly buffer overflow: buffered=" + this.currentBufferSize + ", incoming=" + data.length + ", max=" + this.maxBufferSize);
            return false;
        }

        // Handle overlap with existing fragments.
        // The simple strategy here is overwrite-on-insert: later data wins in overlapping regions.
        this.fragments.put(offset, data);
        this.currentBufferSize += data.length;

        if (fin) {
            this.finReceived = true;
            this.finalOffset = offset + data.length;
        }
        return true;
    }

    /**
     * Reads and removes all contiguous data starting at {@link #nextExpectedOffset}.
     * @return returns the corresponding byte array when contiguous data exists, otherwise {@code null}
     */
    synchronized byte[] readContiguous() {
        if (this.fragments.isEmpty()) {
            return null;
        }

        // Check whether the current expected offset is available.
        Map.Entry<Long, byte[]> first = this.fragments.firstEntry();
        if (first == null || first.getKey() > this.nextExpectedOffset) {
            return null; // A gap exists, so delivery is not possible yet.
        }

        // Collect contiguous fragments.
        int totalLen = 0;
        List<Map.Entry<Long, byte[]>> contiguous = new ArrayList<>();
        long expected = this.nextExpectedOffset;

        while (true) {
            Map.Entry<Long, byte[]> entry = this.fragments.firstEntry();
            if (entry == null) {
                break;
            }
            long fragOffset = entry.getKey();
            byte[] fragData = entry.getValue();

            if (fragOffset > expected) {
                break; // Gap found.
            }

            // The fragment starts before expected or exactly at it.
            if (fragOffset + fragData.length > expected) {
                // This fragment provides new deliverable bytes.
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

        // Assemble contiguous data.
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

    /**
     * Returns the next expected offset, which equals the total bytes delivered so far.
     */
    synchronized long getNextExpectedOffset() {
        return this.nextExpectedOffset;
    }

    /**
     * Returns the total number of bytes already delivered to the application layer.
     */
    synchronized long getTotalBytesDelivered() {
        return this.totalBytesDelivered;
    }

    /**
     * Returns whether FIN has been received.
     */
    synchronized boolean isFinReceived() {
        return this.finReceived;
    }

    /**
     * Returns whether all data through FIN has already been fully delivered.
     */
    synchronized boolean isComplete() {
        return this.finReceived && this.nextExpectedOffset >= this.finalOffset && this.fragments.isEmpty();
    }

    /**
     * Returns the number of bytes currently buffered.
     */
    synchronized long getBufferedSize() {
        return this.currentBufferSize;
    }

    /**
     * Returns whether any buffered out-of-order fragments remain.
     */
    synchronized boolean hasBufferedFragments() {
        return !this.fragments.isEmpty();
    }
}
