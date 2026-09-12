/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.cobble.logging.Logger;
/**
 * NewReno congestion controller implemented according to RFC 9002 Section 7.
 * <p>Covers slow start, congestion avoidance, and recovery.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicCongestionControl {
    /** RFC 9002 §7.2: initial congestion window, 14720 bytes, approximately 10 packets at 1472 MTU. */
    static final long           INITIAL_WINDOW    = 14720;
    /** RFC 9002 §7.2: minimum congestion window, equal to 2 times the maximum datagram size. */
    static final long           MINIMUM_WINDOW    = 2 * 1472;
    /** Maximum datagram size, approximating Ethernet MTU minus UDP/IP overhead. */
    static final long           MAX_DATAGRAM_SIZE = 1472;
    private static final Logger logger            = Logger.getLogger(QuicCongestionControl.class);
    /** Current congestion window in bytes. */
    private long                cwnd              = INITIAL_WINDOW;
    /** Current slow-start threshold in bytes. */
    private long                ssthresh          = Long.MAX_VALUE;
    /** Current congestion-control state. */
    private State               state             = State.SLOW_START;
    /** Packet number at which recovery started; -1 means the controller is not currently in recovery. */
    private long                recoveryStartPn   = -1;
    /** ECN-CE counter corresponding to RFC 9002 Section 7.1. */
    private long                ecnCeCount        = 0;

    /**
     * Determines whether more data can still be sent based on the current bytes in flight.
     */
    synchronized boolean canSend(long bytesInFlight) {
        return bytesInFlight < this.cwnd;
    }

    /**
     * Returns the current congestion window size.
     */
    synchronized long getCwnd() {
        return this.cwnd;
    }

    /**
     * Returns the current slow-start threshold.
     */
    synchronized long getSsthresh() {
        return this.ssthresh;
    }

    /**
     * Returns the current congestion-control state.
     */
    synchronized State getState() {
        return this.state;
    }

    /**
     * Updates the congestion window after acknowledgments are received.
     * <p>Adjusts cwnd according to the current phase, either slow start or congestion avoidance.
     */
    synchronized void onPacketsAcked(long ackedBytes, long packetNumber) {
        if (this.state == State.RECOVERY) {
            // Do not grow cwnd during recovery until recovery ends.
            if (packetNumber > this.recoveryStartPn) {
                // Exit recovery.
                this.state = State.CONGESTION_AVOIDANCE;
                logger.info("Exiting recovery at PN=" + packetNumber + ", cwnd=" + this.cwnd);
            }
            return;
        }

        if (this.state == State.SLOW_START) {
            // RFC 9002 §7.3.1: grow cwnd by the acknowledged byte count.
            this.cwnd += ackedBytes;
            if (this.cwnd >= this.ssthresh) {
                this.state = State.CONGESTION_AVOIDANCE;
                logger.info("Entering congestion avoidance, cwnd=" + this.cwnd + ", ssthresh=" + this.ssthresh);
            }
        } else {
            // Congestion avoidance (RFC 9002 §7.3.3): additive increase.
            // cwnd += MAX_DATAGRAM_SIZE * ackedBytes / cwnd
            this.cwnd += MAX_DATAGRAM_SIZE * ackedBytes / this.cwnd;
        }
    }

    /**
     * Called when packet loss is detected.
     * <p>Enters recovery, halves cwnd, and sets a new ssthresh.
     */
    synchronized void onPacketLost(long lostPacketNumber) {
        if (this.state == State.RECOVERY) {
            // Already in recovery, so do not reduce cwnd again.
            return;
        }

        // RFC 9002 §7.3.2: enter recovery.
        this.recoveryStartPn = lostPacketNumber;
        this.ssthresh = Math.max(this.cwnd / 2, MINIMUM_WINDOW);
        this.cwnd = this.ssthresh;
        this.state = State.RECOVERY;
        logger.info("Congestion event: packet " + lostPacketNumber + " lost, cwnd=" + this.cwnd + ", ssthresh=" + this.ssthresh);
    }

    /**
     * Called when persistent congestion is detected.
     * <p>Resets cwnd to the minimum window.
     */
    synchronized void onPersistentCongestion() {
        this.cwnd = MINIMUM_WINDOW;
        this.ssthresh = this.cwnd;
        this.state = State.SLOW_START;
        logger.info("Persistent congestion detected, cwnd reset to " + this.cwnd);
    }

    /**
     * Called when ECN-CE is reported by an ACK frame.
     * <p>The current implementation treats it as a single packet-loss event.
     */
    synchronized void onEcnCongestion(long ceCount, long sentPacketNumber) {
        if (ceCount <= this.ecnCeCount) {
            return; // Not a new congestion signal.
        }
        this.ecnCeCount = ceCount;
        onPacketLost(sentPacketNumber); // Treat as a single packet-loss event.
    }

    /**
     * Resets the congestion controller state.
     * <p>For example, this can be used when estimation needs to restart after connection migration.
     */
    synchronized void reset() {
        this.cwnd = INITIAL_WINDOW;
        this.ssthresh = Long.MAX_VALUE;
        this.state = State.SLOW_START;
        this.recoveryStartPn = -1;
        this.ecnCeCount = 0;
    }

    /**
     * Congestion-control state.
     */
    enum State {
        SLOW_START,
        CONGESTION_AVOIDANCE,
        RECOVERY
    }
}
