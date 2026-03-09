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
import net.hasor.neta.channel.NetMonitor;

/**
 * QUIC-specific extension of {@link NetMonitor} that exposes live QUIC connection state for debugging.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicMonitor extends NetMonitor {
    private final QuicChannelAsync connCh;

    QuicMonitor(QuicChannelAsync connCh) {
        this.connCh = connCh;
    }

    // ── Handshake ──────────────────────────────────────────────────────

    /** Returns true if the TLS handshake has fully completed (ESTABLISHED state). */
    public boolean isHandshake() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null && hs.isEstablished();
    }

    /** Returns the current handshake phase, or null if no handshake handler is present. */
    public QuicHandshakeState getHandshakeState() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getHandshakeState() : null;
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    /** Returns whether this connection has been closed. */
    public boolean isClosed() {
        return this.connCh.isClosed();
    }

    /** Returns whether this is a client-initiated connection ({@code false} = server side). */
    public boolean isClientMode() {
        return this.connCh.isClientMode();
    }

    // ── 0-RTT Buffering ────────────────────────────────────────────────

    /** Returns true if there are 0-RTT packets currently buffered waiting for handshake completion. */
    public boolean has0RttData() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null && hs.has0RttData();
    }

    /** Returns the number of 0-RTT packets currently buffered (non-destructive; max 64). */
    public int getBuffered0RttPacketCount() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getBuffered0RttCount() : 0;
    }

    // ── Packet Numbers ─────────────────────────────────────────────────

    /** Returns the packet number of the most recently sent 1-RTT packet, or -1 if none sent. */
    public long getLastSndAppPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getLastSndAppPacketNumber() : -1;
    }

    /** Returns the largest received 1-RTT packet number, or -1 if none received. */
    public long getMaxRcvAppPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getLastRcvAppPacketNumber() : -1;
    }

    /** Returns the largest received Initial-level packet number, or -1 if none received. */
    public long getMaxRcvInitialPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getMaxInitialPacketNumber() : -1;
    }

    /** Returns the largest received Handshake-level packet number, or -1 if none received. */
    public long getMaxRcvHandshakePacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getMaxHandshakePacketNumber() : -1;
    }

    // ── Streams ────────────────────────────────────────────────────────

    /**
     * Returns the number of currently active (open) streams on this connection.
     */
    public int getActiveStreamCount() {
        return this.connCh.getActiveStreamCount();
    }

    // ── Flow Control / Negotiation ─────────────────────────────────────

    /** Returns the currently negotiated connection-level max data reflecting the latest MAX_DATA frame from the peer. */
    public long getNegotiationMaxData() {
        return this.connCh.getConnectionMaxData();
    }

    /** Returns the peer's advertised maximum number of simultaneous bidirectional streams. */
    public long getPeerMaxStreamsBidi() {
        return this.connCh.getPeerMaxStreamsBidi();
    }

    /** Returns the peer's advertised maximum number of simultaneous unidirectional streams. */
    public long getPeerMaxStreamsUni() {
        return this.connCh.getPeerMaxStreamsUni();
    }

    // ── Activity ───────────────────────────────────────────────────────

    /** Returns the epoch-ms timestamp of the last connection-level activity (any packet sent or received). */
    public long getLastConnectionActivityTime() {
        return this.connCh.getLastActivityTime();
    }

    // ── PING ───────────────────────────────────────────────────────────

    /** Returns the number of in-flight PING requests sent but not yet ACKed. */
    public int getPendingPingCount() {
        return this.connCh.getPendingPingCount();
    }
    // ── ACK Tracker ─────────────────────────────────────────

    /** Returns the largest 1-RTT packet number received from the peer, or -1 if none received. */
    public long getAckLargestReceivedPn() {
        return this.connCh.getAckTracker().getLargestReceivedPn();
    }

    /** Returns the number of ack-eliciting packets received but not yet acknowledged. */
    public int getAckPendingCount() {
        return this.connCh.getAckTracker().getPendingAckEliciting();
    }

    // ── RTT / Loss Detection ───────────────────────────────

    /** Returns the smoothed RTT (SRTT) in milliseconds (RFC 9002 §5.3); initial value is 333ms. */
    public long getSmoothedRttMs() {
        return this.connCh.getSentPacketTracker().getSmoothedRtt();
    }

    /** Returns the minimum RTT observed in milliseconds; falls back to smoothed RTT if no sample yet. */
    public long getMinRttMs() {
        return this.connCh.getSentPacketTracker().getMinRtt();
    }

    /**
     * Returns the RTT variation (RTTVAR) in milliseconds per RFC 9002 §5.3.
     */
    public long getRttVarMs() {
        return this.connCh.getSentPacketTracker().getRttVar();
    }

    /** Returns the total bytes currently in flight (sent but not yet acked or declared lost) per RFC 9002 §5. */
    public long getBytesInFlight() {
        return this.connCh.getSentPacketTracker().getBytesInFlight();
    }

    /**
     * Returns the number of unacknowledged sent packets currently in flight.
     */
    public int getPacketsInFlight() {
        return this.connCh.getSentPacketTracker().getUnackedCount();
    }

    // ── Congestion Control ─────────────────────────────────

    /** Returns the current congestion window in bytes; initial value is 14720 (RFC 9002 §7.2). */
    public long getCongestionWindow() {
        return this.connCh.getCongestionControl().getCwnd();
    }

    /** Returns the slow-start threshold in bytes; Long.MAX_VALUE before any congestion event (RFC 9002 §7.3). */
    public long getSsthresh() {
        return this.connCh.getCongestionControl().getSsthresh();
    }

    /** Returns the congestion control state: SLOW_START, CONGESTION_AVOIDANCE, or RECOVERY (RFC 9002 §7.3). */
    public QuicCongestionControl.State getCongestionState() {
        return this.connCh.getCongestionControl().getState();
    }

    // ── Flow Control ───────────────────────────────────────

    /** Returns the total bytes received at connection level, counted against the MAX_DATA limit. */
    public long getConnectionBytesReceived() {
        return this.connCh.getFlowControl().getConnectionBytesReceived();
    }
}
