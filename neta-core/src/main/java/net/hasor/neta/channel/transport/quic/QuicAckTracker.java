/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
/**
 * Tracks received packet numbers and generates ACK frames according to RFC 9000 Sections 13.2 and 19.3.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAckTracker {
    /** Received packet-number set, kept ordered for easy range computation. */
    private final TreeSet<Long> receivedPns           = new TreeSet<>();
    /** Threshold of accumulated ack-eliciting packets before generating an ACK; RFC recommends 2. */
    private final int           ackElicitingThreshold = 2;
    /** Largest packet number received so far; -1 means nothing has been received yet. */
    private long                largestReceivedPn     = -1;
    /** Timestamp in milliseconds when the largest packet number was received. */
    private long                largestReceivedTime   = 0;
    /** Number of ack-eliciting packets received since the last ACK was sent. */
    private int                 pendingAckEliciting   = 0;
    /** Whether any out-of-order packet has been received since the last ACK was sent. */
    private boolean             hasGap                = false;
    /** ACK delay exponent used to encode the ack_delay field; defaults to 3. */
    private int                 ackDelayExponent      = 3;
    /** Maximum ACK delay in milliseconds; defaults to 25 ms. */
    private long                maxAckDelay           = 25;
    /** Timestamp in milliseconds when the last ACK frame was sent. */
    private long                lastAckSentTime       = 0;

    /**
     * Parses ACK ranges from the contents of a received ACK frame.
     */
    static List<long[]> parseAckRanges(byte[] data, int pos) {
        List<long[]> ackedRanges = new ArrayList<>();
        long[] tmp = QuicVarInt.decode(data, pos);
        long largestAcked = tmp[0];
        pos += (int) tmp[1];

        tmp = QuicVarInt.decode(data, pos);
        // ackDelay is not used directly for loss detection here.
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

    /** Sets the ACK delay exponent. */
    void setAckDelayExponent(int exponent) {
        this.ackDelayExponent = exponent;
    }

    /** Sets the maximum ACK delay in milliseconds. */
    void setMaxAckDelay(long maxAckDelayMs) {
        this.maxAckDelay = maxAckDelayMs;
    }

    /** Records a received packet number and updates out-of-order state and ack-eliciting counters. */
    synchronized void onPacketReceived(long pn, boolean ackEliciting) {
        // Detect whether an out-of-order gap exists.
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

    /** Returns whether an ACK should be sent immediately. */
    synchronized boolean shouldSendAck() {
        if (this.pendingAckEliciting <= 0) {
            return false;
        }
        // Acknowledge immediately when reordering is observed.
        if (this.hasGap) {
            return true;
        }
        // Threshold reached.
        if (this.pendingAckEliciting >= this.ackElicitingThreshold) {
            return true;
        }
        // Maximum ACK delay exceeded.
        if (this.lastAckSentTime > 0 && (System.currentTimeMillis() - this.lastAckSentTime) >= this.maxAckDelay) {
            return true;
        }
        // First ACK transmission.
        return this.lastAckSentTime == 0 && this.pendingAckEliciting > 0;
    }

    /**
     * Generates an ACK frame for all packet numbers received so far.
     * @return returns null if there is currently nothing to acknowledge
     */
    synchronized byte[] generateAckFrame() {
        if (this.receivedPns.isEmpty()) {
            return null;
        }

        // Compute contiguous ranges from the received packet set, sorted from high to low.
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

        // The first ACK range length equals the distance from largestAcked to the low end of the range.
        long firstAckRange = ranges.get(0)[0] - ranges.get(0)[1];
        int ackRangeCount = ranges.size() - 1;

        // Build the frame bytes.
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.ACK);
        byte[] largestBytes = QuicVarInt.encode(largestAcked);
        byte[] delayBytes = QuicVarInt.encode(ackDelay);
        byte[] countBytes = QuicVarInt.encode(ackRangeCount);
        byte[] firstRangeBytes = QuicVarInt.encode(firstAckRange);

        // Compute fields for additional ranges.
        List<byte[]> additionalRangeBytes = new ArrayList<>();
        for (int i = 1; i < ranges.size(); i++) {
            long prevLow = ranges.get(i - 1)[1]; // Low end of the previous range
            long currHigh = ranges.get(i)[0];     // High end of the current range
            long gap = prevLow - currHigh - 2;    // gap = number of missing packet numbers - 1
            long ackRange = ranges.get(i)[0] - ranges.get(i)[1]; // range length - 1
            byte[] gapBytes = QuicVarInt.encode(Math.max(gap, 0));
            byte[] rangeBytes = QuicVarInt.encode(ackRange);
            additionalRangeBytes.add(gapBytes);
            additionalRangeBytes.add(rangeBytes);
        }

        // Compute total length.
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

        // Reset state counters.
        this.pendingAckEliciting = 0;
        this.hasGap = false;
        this.lastAckSentTime = System.currentTimeMillis();

        return frame;
    }

    /** Computes contiguous ranges from the sorted set of received packet numbers. */
    private List<long[]> computeRanges() {
        if (this.receivedPns.isEmpty()) {
            return Collections.emptyList();
        }

        List<long[]> ranges = new ArrayList<>();
        long rangeHigh = -1;
        long rangeLow = -1;

        // Iterate in descending order.
        for (Long pn : this.receivedPns.descendingSet()) {
            if (rangeHigh < 0) {
                rangeHigh = pn;
                rangeLow = pn;
            } else if (pn == rangeLow - 1) {
                rangeLow = pn; // Extend the current range.
            } else {
                // Save the current range and start a new one.
                ranges.add(new long[] { rangeHigh, rangeLow });
                rangeHigh = pn;
                rangeLow = pn;
            }
        }
        // Do not miss the last range.
        if (rangeHigh >= 0) {
            ranges.add(new long[] { rangeHigh, rangeLow });
        }
        return ranges;
    }

    /** Returns the largest packet number received so far. */
    synchronized long getLargestReceivedPn() {
        return this.largestReceivedPn;
    }

    /** Returns the number of ack-eliciting packets currently pending acknowledgment. */
    synchronized int getPendingAckEliciting() {
        return this.pendingAckEliciting;
    }
}
