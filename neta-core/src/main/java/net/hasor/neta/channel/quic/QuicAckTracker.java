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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Tracks received packet numbers and generates ACK frames (RFC 9000 §19.3).
 * <p>
 * Each QUIC encryption level (Initial, Handshake, 1-RTT) should have its own
 * {@code QuicAckTracker} instance. The tracker records received packet numbers,
 * computes contiguous ranges, and produces ACK frame bytes ready to embed in
 * outgoing QUIC packets.
 * <p>
 * <b>ACK generation policy (RFC 9000 §13.2):</b>
 * <ul>
 *   <li>An ACK frame MUST be generated after receiving at least
 *       {@link #ackElicitingThreshold} ack-eliciting packets.</li>
 *   <li>Out-of-order packets trigger immediate ACK.</li>
 *   <li>{@link #maxAckDelay} limits how long an ACK can be deferred (default 25 ms).</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAckTracker {
    /** Received packet numbers (sorted set for efficient range computation). */
    private final TreeSet<Long> receivedPns           = new TreeSet<>();
    /** Number of ack-eliciting packets to receive before generating an ACK. RFC recommends 2. */
    private final int           ackElicitingThreshold = 2;
    /** The largest packet number received so far (-1 means none). */
    private       long          largestReceivedPn     = -1;
    /** Timestamp (epoch ms) when the largest packet number was received. */
    private       long          largestReceivedTime   = 0;
    /** Number of ack-eliciting packets received since last ACK was sent. */
    private       int           pendingAckEliciting   = 0;
    /** Whether we have received any out-of-order packets since last ACK. */
    private       boolean       hasGap                = false;
    /** ACK delay exponent for encoding the ack_delay field (RFC 9000 §19.3). Default 3 (= divide by 8). */
    private       int           ackDelayExponent      = 3;
    /** Maximum ACK delay in milliseconds (RFC 9000 §18.2: max_ack_delay, default 25 ms). */
    private       long          maxAckDelay           = 25;
    /** Timestamp when the last ACK frame was sent (epoch ms). */
    private       long          lastAckSentTime       = 0;

    /** Parses ACK ranges from a received ACK frame body (after the frame type). Returns acknowledged packet numbers. */
    static List<long[]> parseAckRanges(byte[] data, int pos) {
        List<long[]> ackedRanges = new ArrayList<>();
        long[] tmp = QuicVarInt.decode(data, pos);
        long largestAcked = tmp[0];
        pos += (int) tmp[1];

        tmp = QuicVarInt.decode(data, pos);
        // long ackDelay = tmp[0]; // not used for loss detection directly here
        pos += (int) tmp[1];

        tmp = QuicVarInt.decode(data, pos);
        long ackRangeCount = tmp[0];
        pos += (int) tmp[1];

        tmp = QuicVarInt.decode(data, pos);
        long firstAckRange = tmp[0];
        pos += (int) tmp[1];

        // First range: [largestAcked - firstAckRange, largestAcked]
        long rangeHigh = largestAcked;
        long rangeLow = largestAcked - firstAckRange;
        ackedRanges.add(new long[] { rangeLow, rangeHigh });

        long smallest = rangeLow;
        for (long i = 0; i < ackRangeCount; i++) {
            tmp = QuicVarInt.decode(data, pos);
            long gap = tmp[0];
            pos += (int) tmp[1];

            tmp = QuicVarInt.decode(data, pos);
            long ackRange = tmp[0];
            pos += (int) tmp[1];

            rangeHigh = smallest - gap - 2;
            rangeLow = rangeHigh - ackRange;
            ackedRanges.add(new long[] { rangeLow, rangeHigh });
            smallest = rangeLow;
        }
        return ackedRanges;
    }

    /** Sets the ACK delay exponent (RFC 9000 §18.2, default 3). */
    void setAckDelayExponent(int exponent) {
        this.ackDelayExponent = exponent;
    }

    /** Sets the maximum ACK delay in milliseconds (RFC 9000 §18.2, default 25 ms). */
    void setMaxAckDelay(long maxAckDelayMs) {
        this.maxAckDelay = maxAckDelayMs;
    }

    /**
     * Records a received packet number. Should be called for every packet
     * successfully processed at this encryption level.
     * @param pn the packet number
     * @param ackEliciting {@code true} if the packet contains ack-eliciting frames
     * (i.e. anything other than ACK, PADDING, CONNECTION_CLOSE)
     */
    synchronized void onPacketReceived(long pn, boolean ackEliciting) {
        // Detect gap (out-of-order)
        if (this.largestReceivedPn >= 0 && pn != this.largestReceivedPn + 1) {
            this.hasGap = true;
        }

        this.receivedPns.add(pn);

        if (pn > this.largestReceivedPn) {
            this.largestReceivedPn = pn;
            this.largestReceivedTime = System.currentTimeMillis();
        }

        if (ackEliciting) {
            this.pendingAckEliciting++;
        }
    }

    /**
     * Returns {@code true} if an ACK frame should be generated now.
     * <p>Conditions (RFC 9000 §13.2):
     * <ul>
     *   <li>Received at least {@code ackElicitingThreshold} ack-eliciting packets.</li>
     *   <li>Out-of-order data was received (gap detected).</li>
     *   <li>The max ACK delay has elapsed since the last ACK.</li>
     * </ul>
     */
    synchronized boolean shouldSendAck() {
        if (this.pendingAckEliciting <= 0) {
            return false;
        }
        // Immediate ACK on gaps (out-of-order)
        if (this.hasGap) {
            return true;
        }
        // Threshold reached
        if (this.pendingAckEliciting >= this.ackElicitingThreshold) {
            return true;
        }
        // Max ACK delay elapsed
        if (this.lastAckSentTime > 0 && (System.currentTimeMillis() - this.lastAckSentTime) >= this.maxAckDelay) {
            return true;
        }
        // First ACK ever (no previous ACK sent)
        return this.lastAckSentTime == 0 && this.pendingAckEliciting > 0;
    }

    /**
     * Generates an ACK frame (RFC 9000 §19.3) for all packet numbers received so far.
     * Resets the pending-ack-eliciting counter.
     * @return the encoded ACK frame bytes, or {@code null} if nothing to acknowledge
     */
    synchronized byte[] generateAckFrame() {
        if (this.receivedPns.isEmpty()) {
            return null;
        }

        // Compute contiguous ranges from the set of received packet numbers.
        // Ranges are in descending order: [largest..end], gap, [next..end], ...
        List<long[]> ranges = computeRanges();
        if (ranges.isEmpty()) {
            return null;
        }

        long largestAcked = ranges.get(0)[0]; // highest PN
        long ackDelay = 0;
        if (this.largestReceivedTime > 0) {
            ackDelay = (System.currentTimeMillis() - this.largestReceivedTime) * 1000; // microseconds
            ackDelay = ackDelay >> this.ackDelayExponent; // encode per RFC 9000 §19.3
        }

        // First ACK Range = largest - ranges[0][1] (the range that includes largestAcked)
        long firstAckRange = ranges.get(0)[0] - ranges.get(0)[1];
        int ackRangeCount = ranges.size() - 1;

        // Build frame bytes
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.ACK);
        byte[] largestBytes = QuicVarInt.encode(largestAcked);
        byte[] delayBytes = QuicVarInt.encode(ackDelay);
        byte[] countBytes = QuicVarInt.encode(ackRangeCount);
        byte[] firstRangeBytes = QuicVarInt.encode(firstAckRange);

        // Calculate additional ranges size
        List<byte[]> additionalRangeBytes = new ArrayList<>();
        for (int i = 1; i < ranges.size(); i++) {
            long prevLow = ranges.get(i - 1)[1]; // low end of previous range
            long currHigh = ranges.get(i)[0];     // high end of current range
            long gap = prevLow - currHigh - 2;    // RFC 9000 §19.3: gap = # missing PNs - 1
            long ackRange = ranges.get(i)[0] - ranges.get(i)[1]; // range length - 1
            byte[] gapBytes = QuicVarInt.encode(Math.max(gap, 0));
            byte[] rangeBytes = QuicVarInt.encode(ackRange);
            additionalRangeBytes.add(gapBytes);
            additionalRangeBytes.add(rangeBytes);
        }

        // Compute total length
        int totalLen = typeBytes.length + largestBytes.length + delayBytes.length + countBytes.length + firstRangeBytes.length;
        for (byte[] b : additionalRangeBytes) {
            totalLen += b.length;
        }

        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(largestBytes, 0, frame, pos, largestBytes.length);
        pos += largestBytes.length;
        System.arraycopy(delayBytes, 0, frame, pos, delayBytes.length);
        pos += delayBytes.length;
        System.arraycopy(countBytes, 0, frame, pos, countBytes.length);
        pos += countBytes.length;
        System.arraycopy(firstRangeBytes, 0, frame, pos, firstRangeBytes.length);
        pos += firstRangeBytes.length;
        for (byte[] b : additionalRangeBytes) {
            System.arraycopy(b, 0, frame, pos, b.length);
            pos += b.length;
        }

        // Reset counters
        this.pendingAckEliciting = 0;
        this.hasGap = false;
        this.lastAckSentTime = System.currentTimeMillis();

        return frame;
    }

    /**
     * Computes contiguous ranges from the sorted set of received PNs.
     * Each range is {@code [high, low]} (inclusive). Ranges are sorted descending.
     */
    private List<long[]> computeRanges() {
        if (this.receivedPns.isEmpty()) {
            return Collections.emptyList();
        }

        List<long[]> ranges = new ArrayList<>();
        long rangeHigh = -1;
        long rangeLow = -1;

        // Iterate in descending order
        for (Long pn : this.receivedPns.descendingSet()) {
            if (rangeHigh < 0) {
                rangeHigh = pn;
                rangeLow = pn;
            } else if (pn == rangeLow - 1) {
                rangeLow = pn; // extend current range
            } else {
                // Save current range and start a new one
                ranges.add(new long[] { rangeHigh, rangeLow });
                rangeHigh = pn;
                rangeLow = pn;
            }
        }
        // Don't forget the last range
        if (rangeHigh >= 0) {
            ranges.add(new long[] { rangeHigh, rangeLow });
        }
        return ranges;
    }

    /** Returns the largest packet number received, or -1 if no packets have been received. */
    synchronized long getLargestReceivedPn() {
        return this.largestReceivedPn;
    }

    /** Returns the number of pending ack-eliciting packets. */
    synchronized int getPendingAckEliciting() {
        return this.pendingAckEliciting;
    }
}
