/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.neta.channel.NetMonitor;
/**
 * QUIC-specific extension view of {@link NetMonitor}, exposing runtime state and debugging metrics.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicMonitor extends NetMonitor {
    private final QuicChannelAsync connCh;

    QuicMonitor(QuicChannelAsync connCh) {
        this.connCh = connCh;
    }

    // ── Handshake state ───────────────────────────────────────────────

    /**
     * Returns whether the TLS handshake has completed fully.
     */
    public boolean isHandshake() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null && hs.isEstablished();
    }

    /**
     * Returns the current handshake phase.
     * @return returns null if no handshake handler is currently present
     */
    public QuicHandshakeState getHandshakeState() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getHandshakeState() : null;
    }

    // ── Connection lifecycle ──────────────────────────────────────────

    /**
     * Returns whether the connection has already been closed.
     */
    public boolean isClosed() {
        return this.connCh.isClosed();
    }

    /**
     * Returns whether this connection was initiated by the client.
     * @return true for client mode, false for a server-side connection
     */
    public boolean isClientMode() {
        return this.connCh.isClientMode();
    }

    // ── 0-RTT buffering ───────────────────────────────────────────────

    /**
     * Returns whether any 0-RTT packets are currently waiting to be processed after handshake completion.
     */
    public boolean has0RttData() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null && hs.has0RttData();
    }

    /**
     * Returns the number of 0-RTT packets currently buffered.
     */
    public int getBuffered0RttPacketCount() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getBuffered0RttCount() : 0;
    }

    // ── Packet number statistics ──────────────────────────────────────

    /**
     * Returns the packet number of the most recently sent 1-RTT packet.
     * @return returns -1 if nothing has been sent yet
     */
    public long getLastSndAppPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getLastSndAppPacketNumber() : -1;
    }

    /**
     * Returns the largest 1-RTT packet number received so far.
     * @return returns -1 if nothing has been received yet
     */
    public long getMaxRcvAppPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getLastRcvAppPacketNumber() : -1;
    }

    /**
     * Returns the largest Initial-level packet number received so far.
     * @return returns -1 if nothing has been received yet
     */
    public long getMaxRcvInitialPacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getMaxInitialPacketNumber() : -1;
    }

    /**
     * Returns the largest Handshake-level packet number received so far.
     * @return returns -1 if nothing has been received yet
     */
    public long getMaxRcvHandshakePacketNumber() {
        QuicAsyncChannelHandshake hs = this.connCh.getHandshake();
        return hs != null ? hs.getMaxHandshakePacketNumber() : -1;
    }

    // ── Stream state ──────────────────────────────────────────────────

    /**
     * Returns the number of streams currently active on the connection.
     */
    public int getActiveStreamCount() {
        return this.connCh.getActiveStreamCount();
    }

    // ── Flow control and negotiated results ───────────────────────────

    /**
     * Returns the currently negotiated connection-level maximum data limit.
     * <p>This value reflects the latest MAX_DATA update from the peer.
     */
    public long getNegotiationMaxData() {
        return this.connCh.getConnectionMaxData();
    }

    /**
     * Returns the maximum number of concurrent bidirectional streams advertised by the peer.
     */
    public long getPeerMaxStreamsBidi() {
        return this.connCh.getPeerMaxStreamsBidi();
    }

    /**
     * Returns the maximum number of concurrent unidirectional streams advertised by the peer.
     */
    public long getPeerMaxStreamsUni() {
        return this.connCh.getPeerMaxStreamsUni();
    }

    // ── Activity time ─────────────────────────────────────────────────

    /**
     * Returns the timestamp of the most recent connection-level activity.
     * <p>The value is in milliseconds and includes any packet send or receive activity.
     */
    public long getLastConnectionActivityTime() {
        return this.connCh.getLastActivityTime();
    }

    // ── PING ──────────────────────────────────────────────────────────

    /**
     * Returns the number of in-flight PING requests that have not yet been acknowledged.
     */
    public int getPendingPingCount() {
        return this.connCh.getPendingPingCount();
    }

    // ── ACK tracking ──────────────────────────────────────────────────

    /**
     * Returns the largest 1-RTT packet number received from the peer.
     * @return returns -1 if nothing has been received yet
     */
    public long getAckLargestReceivedPn() {
        return this.connCh.getAckTracker().getLargestReceivedPn();
    }

    /**
     * Returns the number of ack-eliciting packets that have been received but not yet acknowledged.
     */
    public int getAckPendingCount() {
        return this.connCh.getAckTracker().getPendingAckEliciting();
    }

    // ── RTT and loss detection ────────────────────────────────────────

    /**
     * Returns the smoothed RTT, that is, SRTT.
     * <p>The value is in milliseconds and starts at 333 ms.
     */
    public long getSmoothedRttMs() {
        return this.connCh.getSentPacketTracker().getSmoothedRtt();
    }

    /**
     * Returns the minimum RTT observed so far.
     * <p>If no sample is available yet, the value falls back to the smoothed RTT.
     */
    public long getMinRttMs() {
        return this.connCh.getSentPacketTracker().getMinRtt();
    }

    /**
     * Returns the RTT variation value, that is, RTTVAR.
     * <p>The value is in milliseconds as defined by RFC 9002 Section 5.3.
     */
    public long getRttVarMs() {
        return this.connCh.getSentPacketTracker().getRttVar();
    }

    /**
     * Returns the total number of bytes currently in flight.
     * <p>This is the amount of data sent but not yet acknowledged or declared lost.
     */
    public long getBytesInFlight() {
        return this.connCh.getSentPacketTracker().getBytesInFlight();
    }

    /**
     * Returns the number of sent packets currently in flight and still unacknowledged.
     */
    public int getPacketsInFlight() {
        return this.connCh.getSentPacketTracker().getUnackedCount();
    }

    // ── Congestion control ────────────────────────────────────────────

    /**
     * Returns the current congestion window size.
     * <p>The value is in bytes and starts at 14720.
     */
    public long getCongestionWindow() {
        return this.connCh.getCongestionControl().getCwnd();
    }

    /**
     * Returns the slow-start threshold.
     * <p>Before the first congestion event, this is usually Long.MAX_VALUE.
     */
    public long getSsthresh() {
        return this.connCh.getCongestionControl().getSsthresh();
    }

    // ── Flow control ──────────────────────────────────────────────────

    /**
     * Returns the total number of bytes received at the connection level.
     * <p>This value counts toward the MAX_DATA limit.
     */
    public long getConnectionBytesReceived() {
        return this.connCh.getFlowControl().getConnectionBytesReceived();
    }
}
