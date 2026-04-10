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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import net.hasor.cobble.logging.Logger;

/**
 * Tracks sent packets and implements loss detection according to RFC 9002 Section 6.
 * <p>Both packet-threshold and time-threshold loss detection are used.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicSentPacketTracker {
    /** RFC 9002 §6.1.1: packet reordering threshold allowed before declaring loss. */
    static final         int                        PACKET_THRESHOLD      = 3;
    private static final Logger                     logger                = Logger.getLogger(QuicSentPacketTracker.class);
    /** RFC 9002 §6.1.2: time reordering threshold factor, set to 9/8 of the maximum RTT. */
    private static final double                     TIME_THRESHOLD_FACTOR = 9.0 / 8.0;
    /** Sent but unacknowledged packet list kept in send order. */
    private final        LinkedList<SentPacketInfo> sentPackets           = new LinkedList<>();
    /** Largest packet number acknowledged so far, or -1 if nothing has been acknowledged yet. */
    private              long                       largestAckedPn        = -1;
    /** Smoothed RTT in milliseconds. */
    private              long                       smoothedRtt           = 333; // Initial estimate is 333 ms (RFC 9002 §6.2.2)
    /** RTT variation in milliseconds. */
    private              long                       rttVar                = 166; // Initial RTTVAR = SRTT / 2
    /** Minimum RTT observed so far, in milliseconds. */
    private              long                       minRtt                = Long.MAX_VALUE;
    /** Latest RTT sample in milliseconds. */
    private              long                       latestRtt             = 0;
    /** Bytes currently in flight, that is, sent but neither acknowledged nor declared lost. */
    private              long                       bytesInFlight         = 0;
    /** Expiration timestamp of the PTO timer in milliseconds; 0 means unset. */
    private              long                       ptoExpiry             = 0;
    /** Number of PTO probes sent while ACKs have not been received consecutively. */
    private              int                        ptoCount              = 0;

    /**
     * Returns whether the given packet number falls inside any acknowledged range.
     */
    private static boolean isAcked(long pn, List<long[]> ackedRanges) {
        for (long[] range : ackedRanges) {
            if (pn >= range[0] && pn <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a sent packet for later loss detection and congestion control.
     */
    synchronized void onPacketSent(long packetNumber, byte[] payload, int size, boolean ackEliciting) {
        SentPacketInfo info = new SentPacketInfo();
        info.packetNumber = packetNumber;
        info.payload = payload;
        info.size = size;
        info.sentTime = System.currentTimeMillis();
        info.ackEliciting = ackEliciting;
        this.sentPackets.add(info);
        if (ackEliciting) {
            this.bytesInFlight += size;
        }
        // Reset the PTO timer.
        if (ackEliciting) {
            setPtoTimer();
        }
    }

    /**
     * Processes received ACK ranges.
     * <p>This updates RTT, removes acknowledged packets, and returns payloads declared lost.
     */
    synchronized List<byte[]> onAckReceived(List<long[]> ackedRanges) {
        if (ackedRanges == null || ackedRanges.isEmpty()) {
            return new ArrayList<>();
        }

        long now = System.currentTimeMillis();

        // Find the largest packet number acknowledged by this ACK.
        long newLargestAcked = -1;
        for (long[] range : ackedRanges) {
            if (range[1] > newLargestAcked) {
                newLargestAcked = range[1];
            }
        }

        // Update the largest acknowledged packet number.
        boolean isNewlyAcked = newLargestAcked > this.largestAckedPn;
        if (isNewlyAcked) {
            this.largestAckedPn = newLargestAcked;
        }

        // Remove acknowledged packets from the sent list and update RTT.
        Iterator<SentPacketInfo> it = this.sentPackets.iterator();
        while (it.hasNext()) {
            SentPacketInfo info = it.next();
            if (isAcked(info.packetNumber, ackedRanges)) {
                // Update RTT using the packet corresponding to the newest largest acknowledged number.
                if (info.packetNumber == newLargestAcked && isNewlyAcked) {
                    long rttSample = now - info.sentTime;
                    updateRtt(rttSample);
                }
                if (info.ackEliciting) {
                    this.bytesInFlight -= info.size;
                }
                it.remove();
            }
        }

        // Detect lost packets.
        List<byte[]> lostPayloads = detectLoss(now);

        // Reset PTO state.
        this.ptoCount = 0;
        if (!this.sentPackets.isEmpty()) {
            setPtoTimer();
        } else {
            this.ptoExpiry = 0;
        }

        return lostPayloads;
    }

    /**
     * Returns whether the PTO timer has expired.
     */
    synchronized boolean isPtoExpired() {
        if (this.ptoExpiry == 0 || this.sentPackets.isEmpty()) {
            return false;
        }
        return System.currentTimeMillis() >= this.ptoExpiry;
    }

    /**
     * Invoked when sending a PTO probe packet.
     * <p>This increments the PTO counter and resets the timer.
     */
    synchronized void onPtoSent() {
        this.ptoCount++;
        setPtoTimer();
    }

    /**
     * Returns the number of bytes currently in flight.
     */
    synchronized long getBytesInFlight() {
        return this.bytesInFlight;
    }

    /**
     * Returns the smoothed RTT.
     */
    synchronized long getSmoothedRtt() {
        return this.smoothedRtt;
    }

    /**
     * Returns the minimum RTT observed so far.
     */
    synchronized long getMinRtt() {
        return this.minRtt == Long.MAX_VALUE ? this.smoothedRtt : this.minRtt;
    }

    /**
     * Returns the RTT variation value.
     */
    synchronized long getRttVar() {
        return this.rttVar;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Returns the number of sent packets that remain unacknowledged.
     */
    synchronized int getUnackedCount() {
        return this.sentPackets.size();
    }

    /**
     * Updates the smoothed RTT and RTT variation according to RFC 9002 Section 5.3.
     */
    private void updateRtt(long rttSample) {
        this.latestRtt = rttSample;
        if (rttSample < this.minRtt) {
            this.minRtt = rttSample;
        }

        // RFC 9002 §5.3: initialize directly on the first sample.
        if (this.smoothedRtt == 333 && this.rttVar == 166) {
            this.smoothedRtt = rttSample;
            this.rttVar = rttSample / 2;
        } else {
            long adjustedRtt = Math.max(rttSample, this.minRtt);
            this.rttVar = (3 * this.rttVar + Math.abs(this.smoothedRtt - adjustedRtt)) / 4;
            this.smoothedRtt = (7 * this.smoothedRtt + adjustedRtt) / 8;
        }
    }

    /**
     * Detects loss using both packet-threshold and time-threshold rules.
     */
    private List<byte[]> detectLoss(long now) {
        List<byte[]> lostPayloads = new ArrayList<>();
        long lossDelay = (long) (Math.max(this.latestRtt, this.smoothedRtt) * TIME_THRESHOLD_FACTOR);
        lossDelay = Math.max(lossDelay, 1); // At least 1 ms.

        Iterator<SentPacketInfo> it = this.sentPackets.iterator();
        while (it.hasNext()) {
            SentPacketInfo info = it.next();
            if (info.packetNumber > this.largestAckedPn) {
                continue; // No newer ACK is available yet, so loss cannot be declared.
            }

            boolean packetThresholdLost = (this.largestAckedPn - info.packetNumber) >= PACKET_THRESHOLD;
            boolean timeThresholdLost = (now - info.sentTime) > lossDelay;

            if (packetThresholdLost || timeThresholdLost) {
                logger.info("Packet " + info.packetNumber + " declared lost (pktThreshold=" + packetThresholdLost + ", timeThreshold=" + timeThresholdLost + ")");
                if (info.payload != null) {
                    lostPayloads.add(info.payload);
                }
                if (info.ackEliciting) {
                    this.bytesInFlight -= info.size;
                }
                it.remove();
            }
        }
        return lostPayloads;
    }

    /**
     * Sets the PTO timer based on the current RTT estimate.
     */
    private void setPtoTimer() {
        long pto = this.smoothedRtt + Math.max(4 * this.rttVar, 1);
        // Apply exponential backoff.
        pto = pto * (1L << this.ptoCount);
        this.ptoExpiry = System.currentTimeMillis() + pto;
    }

    /**
     * Tracking information for a sent packet.
     */
    static class SentPacketInfo {
        long    packetNumber;
        byte[]  payload;
        int     size;
        long    sentTime;
        boolean ackEliciting;
    }
}
