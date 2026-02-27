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
 * Implements QUIC congestion control per RFC 9002 §7 (NewReno-like algorithm).
 * <p>
 * The congestion controller manages the sending rate to avoid overwhelming the
 * network. It maintains a congestion window ({@link #cwnd}) that limits the
 * total number of bytes in flight.
 * <p>
 * <b>States (RFC 9002 §7.3):</b>
 * <ul>
 *   <li><b>Slow Start</b>: cwnd grows by the number of bytes acknowledged, doubling each RTT.</li>
 *   <li><b>Congestion Avoidance</b>: cwnd grows by ~1 MSS per RTT (additive increase).</li>
 *   <li><b>Recovery</b>: after a loss event, cwnd is halved and slow start threshold is set.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicCongestionControl {
    /** RFC 9002 §7.2: initial congestion window (14720 bytes ≈ 10 × 1472 MTU). */
    static final         long   INITIAL_WINDOW    = 14720;
    /** RFC 9002 §7.2: minimum congestion window (2 × max datagram size). */
    static final         long   MINIMUM_WINDOW    = 2 * 1472;
    /** Maximum segment size (approx. Ethernet MTU minus UDP/IP overhead). */
    static final         long   MAX_DATAGRAM_SIZE = 1472;
    private static final Logger logger            = Logger.getLogger(QuicCongestionControl.class);
    /** Current congestion window in bytes. */
    private              long   cwnd              = INITIAL_WINDOW;
    /** Slow start threshold in bytes. */
    private              long   ssthresh          = Long.MAX_VALUE;
    /** Current congestion state. */
    private              State  state             = State.SLOW_START;
    /** Packet number at which recovery was entered (-1 if not in recovery). */
    private              long   recoveryStartPn   = -1;
    /** ECN-CE counter (RFC 9002 §7.1). */
    private              long   ecnCeCount        = 0;

    /**
     * Returns whether we can send more data given current bytes in flight.
     * @param bytesInFlight the number of bytes currently in flight
     * @return {@code true} if sending is allowed
     */
    synchronized boolean canSend(long bytesInFlight) {
        return bytesInFlight < this.cwnd;
    }

    /** Returns the current congestion window in bytes. */
    synchronized long getCwnd() {
        return this.cwnd;
    }

    /** Returns the current slow start threshold. */
    synchronized long getSsthresh() {
        return this.ssthresh;
    }

    /** Returns the current congestion state. */
    synchronized State getState() {
        return this.state;
    }

    /**
     * Called when bytes are acknowledged (RFC 9002 §7.3.1 and §7.3.3).
     * @param ackedBytes the number of bytes newly acknowledged
     * @param packetNumber the packet number that was acknowledged
     */
    synchronized void onPacketsAcked(long ackedBytes, long packetNumber) {
        if (this.state == State.RECOVERY) {
            // In recovery: don't increase cwnd until we exit recovery
            if (packetNumber > this.recoveryStartPn) {
                // Exit recovery
                this.state = State.CONGESTION_AVOIDANCE;
                logger.info("Exiting recovery at PN=" + packetNumber + ", cwnd=" + this.cwnd);
            }
            return;
        }

        if (this.state == State.SLOW_START) {
            // RFC 9002 §7.3.1: increase cwnd by acked bytes
            this.cwnd += ackedBytes;
            if (this.cwnd >= this.ssthresh) {
                this.state = State.CONGESTION_AVOIDANCE;
                logger.info("Entering congestion avoidance, cwnd=" + this.cwnd + ", ssthresh=" + this.ssthresh);
            }
        } else {
            // Congestion avoidance (RFC 9002 §7.3.3): additive increase
            // cwnd += MAX_DATAGRAM_SIZE * ackedBytes / cwnd
            this.cwnd += MAX_DATAGRAM_SIZE * ackedBytes / this.cwnd;
        }
    }

    /**
     * Called when a packet loss is detected (RFC 9002 §7.3.2).
     * Enters recovery state, halves cwnd and sets ssthresh.
     * @param lostPacketNumber the packet number of the lost packet
     */
    synchronized void onPacketLost(long lostPacketNumber) {
        if (this.state == State.RECOVERY) {
            // Already in recovery — do not reduce cwnd again
            return;
        }

        // RFC 9002 §7.3.2: enter recovery
        this.recoveryStartPn = lostPacketNumber;
        this.ssthresh = Math.max(this.cwnd / 2, MINIMUM_WINDOW);
        this.cwnd = this.ssthresh;
        this.state = State.RECOVERY;
        logger.info("Congestion event: packet " + lostPacketNumber + " lost, cwnd=" + this.cwnd + ", ssthresh=" + this.ssthresh);
    }

    /**
     * Called when a persistent congestion event is detected (RFC 9002 §7.6).
     * Resets cwnd to the minimum window.
     */
    synchronized void onPersistentCongestion() {
        this.cwnd = MINIMUM_WINDOW;
        this.ssthresh = this.cwnd;
        this.state = State.SLOW_START;
        logger.info("Persistent congestion detected, cwnd reset to " + this.cwnd);
    }

    /**
     * Called when an ECN-CE marking is received in an ACK frame (RFC 9002 §7.1).
     * Treated as a congestion event similar to packet loss.
     * @param ceCount the new ECN-CE counter value from the ACK-ECN frame
     * @param sentPacketNumber the packet number of the sent packet that triggered the ECN signal
     */
    synchronized void onEcnCongestion(long ceCount, long sentPacketNumber) {
        if (ceCount <= this.ecnCeCount) {
            return; // not a new congestion signal
        }
        this.ecnCeCount = ceCount;
        onPacketLost(sentPacketNumber); // treat as a loss event
    }

    /** Resets the congestion controller (e.g., on connection migration). */
    synchronized void reset() {
        this.cwnd = INITIAL_WINDOW;
        this.ssthresh = Long.MAX_VALUE;
        this.state = State.SLOW_START;
        this.recoveryStartPn = -1;
        this.ecnCeCount = 0;
    }

    /** Congestion states. */
    enum State {
        SLOW_START,
        CONGESTION_AVOIDANCE,
        RECOVERY
    }
}
