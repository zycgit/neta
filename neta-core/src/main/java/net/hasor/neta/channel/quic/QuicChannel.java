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
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetMonitor;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.udp.UdpChannel;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Connection-level QUIC channel, extending {@link UdpChannel}.
 * <p>
 * This channel is created <b>only after the QUIC handshake has completed</b>.
 * All handshake processing (Initial, Handshake packets, TLS, transport-parameter
 * negotiation) is handled by {@link QuicChannelAsync} before this
 * object comes into existence.
 * <p>
 * Once created, this channel handles:
 * <ul>
 *   <li>Application-level (1-RTT) packet processing</li>
 *   <li>Stream management (multiplexed streams over a single connection)</li>
 *   <li>DATAGRAM support (RFC 9221)</li>
 *   <li>Connection-level flow control (RFC 9000 §4)</li>
 *   <li>Connection lifecycle (close, closeNow, closeWithError)</li>
 * </ul>
 * <p>
 * Stream creation is supported via:
 * <ul>
 *   <li>{@link #newBidiStream()} / {@link #newUniStream()} — asynchronously creates a stream
 *       with an automatically allocated ID, using the connection's default pipeline.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannelAsync
 * @see QuicStreamChannel
 */
public class QuicChannel extends UdpChannel {
    private final QuicSoConfig   quicSoConfig;
    private final boolean        clientMode;
    private final AtomicLong     nextBidiStreamId;   // client: 0,4,8,…  server: 1,5,9,…
    private final AtomicLong     nextUniStreamId;    // client: 2,6,10,… server: 3,7,11,…
    private final AtomicLong     localMaxStreamsBidi;// max bidi streams we allow peer to open
    private final AtomicLong     localMaxStreamsUni; // max uni streams we allow peer to open
    private final QuicSslContext sslContext;         // SSL context from completed handshake

    /**
     * Creates a QuicChannel from a completed handshake.
     * All handshake negotiation results are read from the given
     * {@link QuicChannelAsync}.
     */
    QuicChannel(QuicChannelAsync connCh) throws Throwable {
        super(connCh.getChannelId(), new NetMonitor(), connCh.getForListen(), connCh.getInitializer(), connCh, connCh.getContext());
        this.quicSoConfig = connCh.getSoConfig();
        this.clientMode = connCh.isClientMode();
        this.nextBidiStreamId = new AtomicLong(this.clientMode ? 0 : 1);
        this.nextUniStreamId = new AtomicLong(this.clientMode ? 2 : 3);
        this.localMaxStreamsBidi = new AtomicLong(this.quicSoConfig.getTpInitialMaxStreamsBidi());
        this.localMaxStreamsUni = new AtomicLong(this.quicSoConfig.getTpInitialMaxStreamsUni());

        // Build QuicSslContext from handshake results
        QuicAsyncChannelHandshake handshake = connCh.getHandshake();
        QuicTlsEngine tlsEngine = (handshake != null) ? handshake.getTlsEngine() : null;
        if (tlsEngine != null) {
            SslCertConfig certConfig = this.quicSoConfig.getSslConfig();
            String negotiatedAlpn = tlsEngine.getNegotiatedAlpn();
            // Prefer SNI hostname from TLS ClientHello; fall back to socket address
            String sniHost = tlsEngine.getPeerSniHost();
            SocketAddress remote = connCh.getRemoteAddress();
            String peerHost = (sniHost != null && !sniHost.isEmpty()) ? sniHost : null;
            int peerPort = 0;
            if (remote instanceof java.net.InetSocketAddress) {
                java.net.InetSocketAddress inet = (java.net.InetSocketAddress) remote;
                if (peerHost == null) {
                    peerHost = inet.getHostString();
                }
                peerPort = inet.getPort();
            } else if (peerHost == null && remote != null) {
                peerHost = remote.toString();
            }
            String sniHostName = tlsEngine.getPeerSniHost();
            this.sslContext = new QuicSslContext(this, certConfig, this.clientMode, negotiatedAlpn, peerHost, peerPort, sniHostName);
        } else {
            this.sslContext = null;
        }

        connCh.setQuicChannel(this);
    }

    protected QuicChannelAsync asyncChannel() {
        return (QuicChannelAsync) this.asyncChannel;
    }

    /** Returns the {@link SslContext} for this QUIC connection, or {@code null} if SSL is disabled. */
    public SslContext getSslContext() {
        return this.sslContext;
    }

    /** Returns the set of currently open stream IDs (protocol-level tracking). */
    public Set<Long> getOpenStreams() {
        return this.asyncChannel().getStreamIds();
    }

    /**
     * Returns the effective connection-level data limit currently in use.
     * Initially {@code min(localConfig, peerAnnounced)}, and can only <b>increase</b>
     * via {@link #sendMaxDataSize(long)}.
     * @return current effective data limit
     */
    public long getMaxDataSize() {
        return this.asyncChannel().getNegotiationMaxData();
    }

    /**
     * Sends a MAX_DATA frame (RFC 9000 §19.9) to the peer, increasing the connection-level
     * flow control limit. The new limit must be <b>greater than or equal to</b> the current
     * {@link #getMaxDataSize()} — smaller values are rejected.
     * <p>
     * After the frame has been successfully transmitted over UDP, the local
     * {@code useMaxDataSize} is updated to {@code newMaxDataSize}.
     * @param newMaxDataSize the new maximum data limit (must be &ge; current {@code useMaxDataSize})
     * @return a {@link Future} that completes with this {@link QuicChannel} on success
     * @throws IllegalArgumentException if {@code newMaxDataSize} is less than the current limit
     */
    public Future<QuicChannel> sendMaxDataSize(long newMaxDataSize) {
        long currentDataSize = this.asyncChannel().getNegotiationMaxData();
        if (newMaxDataSize < currentDataSize) {
            throw new IllegalArgumentException("maxDataSize can only increase: current=" + currentDataSize + ", requested=" + newMaxDataSize);
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_DATA);
            byte[] valBytes = QuicVarInt.encode(newMaxDataSize);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + valBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(valBytes);
            future.onCompleted(f -> {
                this.asyncChannel().updateGlobalMaxDataSize(newMaxDataSize);
            });
            this.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    // ── Keep-alive / Probe API ─────────────────────────────────────────

    /**
     * Sends a PING frame and returns a {@link Future} that completes with the
     * round-trip time in <b>milliseconds</b> when the peer's ACK is received.
     * <p>Equivalent to {@code ping(0)} — waits indefinitely for the ACK.
     */
    public Future<Long> ping() {
        return this.asyncChannel().sendPingRtt(0);
    }

    /**
     * Sends a PING frame and returns a {@link Future} that completes with the
     * round-trip time in <b>milliseconds</b> when the peer's ACK is received.
     * @param timeoutMs how long to wait before failing with
     * {@link java.util.concurrent.TimeoutException}; {@code 0} = no timeout
     */
    public Future<Long> ping(long timeoutMs) {
        return this.asyncChannel().sendPingRtt(timeoutMs);
    }

    /**
     * Initiates an active connection migration to probe a new network path (RFC 9000 §9).
     * <p>
     * The method issues a fresh local Connection ID, rotates to a new remote CID, resets
     * the congestion controller, and sends a PATH_CHALLENGE frame.  The returned
     * {@link Future} resolves with the measured path RTT (milliseconds) once the peer
     * replies with a matching PATH_RESPONSE, or fails with a
     * {@link java.util.concurrent.TimeoutException} if no response arrives in time.
     * </p>
     * @return future completing with path RTT in milliseconds
     * @throws IOException if the underlying send fails
     */
    public Future<Long> migrate() throws IOException {
        return this.asyncChannel().migrate();
    }

    /**
     * Sends a PATH_CHALLENGE frame with the given 8-byte data.
     * The peer should respond with a PATH_RESPONSE containing the same data.
     * @param data exactly 8 bytes of challenge data
     */
    public Future<QuicChannel> pathChallenge(byte[] data) {
        if (data == null || data.length != 8) {
            throw new IllegalArgumentException("PATH_CHALLENGE data must be exactly 8 bytes");
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_CHALLENGE);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + 8);
            frame.writeBytes(typeBytes);
            frame.writeBytes(data);
            this.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    /**
     * Sends a {@code CONNECTION_CLOSE} frame with the given RFC 9000 transport
     * error code and reason phrase, then immediately tears down the connection.
     * <p>Use this when a protocol violation is detected locally — the peer will
     * receive the error code and can log or report it accordingly.
     * @param errorCode one of the constants in {@link QuicErrorCode}
     * @param reason human-readable reason phrase (may be {@code null})
     * @see QuicErrorCode
     */
    public Future<QuicChannel> closeWithError(long errorCode, String reason) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        this.asyncChannel().closeWithError(errorCode, reason, future);
        return future;
    }

    /**
     * Gracefully closes this QUIC connection by sending a {@code CONNECTION_CLOSE}
     * frame with error code {@link QuicErrorCode#NO_ERROR} (0x00).
     * <p>This is the preferred way to close a QUIC connection when no error has occurred.
     * @return a {@link Future} that completes once the close frame has been sent
     * @see QuicErrorCode#NO_ERROR
     */
    public Future<QuicChannel> closeGracefully() {
        return closeWithError(QuicErrorCode.NO_ERROR, "");
    }

    /**
     * Returns the {@link QuicStreamChannel} for the given stream ID, or {@code null} if none exists.
     * @param streamId the QUIC stream ID to look up (per RFC 9000 §2.1)
     * @return the existing {@link QuicStreamChannel}, or {@code null} if no stream with that ID is open
     */
    public QuicStreamChannel findStream(long streamId) {
        return this.asyncChannel().findStream(streamId);
    }

    // ── Bidi Stream ────────────────────────────────────────────────

    /** Returns the maximum number of bidirectional streams the peer allows us to open. */
    public long getBidiMaxStreams() {
        return this.asyncChannel().getPeerMaxStreamsBidi();
    }

    /**
     * Asynchronously creates a new <b>bidirectional</b> stream channel, automatically
     * allocating the next stream ID for this endpoint.
     * The pipeline is initialized using the connection's default {@link ProtoInitializer}.
     * @return a {@link Future} that completes with the newly opened {@link QuicStreamChannel}
     * @throws IllegalStateException if the peer-advertised bidirectional stream limit is exceeded
     */
    public Future<QuicStreamChannel> newBidiStream() {
        return this.asyncChannel().newStreamChannel(nextBidiStreamId());
    }

    /** the next bidirectional stream ID */
    private long nextBidiStreamId() {
        long id = this.nextBidiStreamId.getAndAdd(4);
        long streamIndex = id / 4;
        long peerMax = this.asyncChannel().getPeerMaxStreamsBidi();
        if (streamIndex >= peerMax) {
            this.nextBidiStreamId.addAndGet(-4); // rollback
            throw new IllegalStateException("Bidirectional stream limit exceeded: " + peerMax);
        }

        return id;
    }

    /**
     * Sends a MAX_STREAMS (bidirectional) frame (RFC 9000 §19.11) to increase the limit on
     * the number of bidirectional streams the peer is allowed to open.
     * The returned {@link Future} completes normally on success or fails with the send exception.
     * @param upgradeIncr the number of <em>additional</em> bidirectional streams to allow
     * @return a {@link Future} that completes with this {@link QuicChannel} on success
     */
    public Future<QuicChannel> upgradeBidiStreams(long upgradeIncr) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            long newMax = this.localMaxStreamsBidi.addAndGet(upgradeIncr);
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_BIDI);
            byte[] valBytes = QuicVarInt.encode(newMax);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + valBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(valBytes);
            future.onFailed(f -> this.localMaxStreamsBidi.addAndGet(-upgradeIncr));
            this.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            this.localMaxStreamsBidi.addAndGet(-upgradeIncr);
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    // ── Uni Stream ────────────────────────────────────────────────

    /** Returns the maximum number of unidirectional streams the peer allows us to open. */
    public long getUniMaxStreams() {
        return this.asyncChannel().getPeerMaxStreamsUni();
    }

    /**
     * Asynchronously creates a new <b>unidirectional</b> stream channel, automatically
     * allocating the next stream ID for this endpoint.
     * The pipeline is initialized using the connection's default {@link ProtoInitializer}.
     * @return a {@link Future} that completes with the newly opened {@link QuicStreamChannel}
     * @throws IllegalStateException if the peer-advertised unidirectional stream limit is exceeded
     */
    public Future<QuicStreamChannel> newUniStream() {
        return this.asyncChannel().newStreamChannel(nextUniStreamId());
    }

    /** the next unidirectional stream ID */
    private long nextUniStreamId() {
        long id = this.nextUniStreamId.getAndAdd(4);
        long streamIndex = id / 4;
        long peerMax = this.asyncChannel().getPeerMaxStreamsUni();
        if (streamIndex >= peerMax) {
            this.nextUniStreamId.addAndGet(-4); // rollback
            throw new IllegalStateException("Unidirectional stream limit exceeded: " + peerMax);
        }

        return id;
    }

    /**
     * Sends a MAX_STREAMS (unidirectional) frame (RFC 9000 §19.11) to increase the limit on
     * the number of unidirectional streams the peer is allowed to open.
     * The returned {@link Future} completes normally on success or fails with the send exception.
     * @param upgradeIncr the number of <em>additional</em> unidirectional streams to allow
     * @return a {@link Future} that completes with this {@link QuicChannel} on success
     */
    public Future<QuicChannel> upgradeUniStreams(long upgradeIncr) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            long newMax = this.localMaxStreamsUni.addAndGet(upgradeIncr);
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_UNI);
            byte[] valBytes = QuicVarInt.encode(newMax);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + valBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(valBytes);
            future.onFailed(f -> this.localMaxStreamsUni.addAndGet(-upgradeIncr));
            this.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            this.localMaxStreamsUni.addAndGet(-upgradeIncr);
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    // ── DATAGRAM API (RFC 9221) ────────────────────────────────────────

    /**
     * Returns {@code true} if DATAGRAM frames are supported on this connection.
     * <p>Support is determined entirely by the handshake negotiation result:
     * <ul>
     *   <li>The local side must have configured a non-zero {@code max_datagram_frame_size}
     *       via {@link QuicSoConfig#setTpInitialDatagramFrameMaxData(long)} before the connection
     *       is established.</li>
     *   <li>The remote peer must have advertised a non-zero {@code max_datagram_frame_size}
     *       transport parameter during the TLS handshake (RFC 9221 §3).</li>
     * </ul>
     * @return {@code true} if both the local and remote sides have negotiated DATAGRAM support
     * @see #getDatagramFrameSize()
     */
    public boolean isSupportDatagram() {
        return this.getDatagramFrameSize() > 0;
    }

    /**
     * Returns the effective maximum DATAGRAM frame payload size for this connection.
     * <p>This is {@code min(localMax, peerMax)} and is fixed once the TLS handshake
     * completes. Returns {@code 0} if DATAGRAM is not supported by either side.
     * Use {@link #isSupportDatagram()} to test support before sending.
     * @return the negotiated max DATAGRAM frame payload size in bytes, or {@code 0} if unsupported
     */
    public long getDatagramFrameSize() {
        return this.asyncChannel().getPeerDatagramMaxData();
    }

    /**
     * Returns the existing DATAGRAM channel, or {@code null} if none has been opened yet.
     * <p>This is a pure lookup — it never creates or initializes a channel.
     * Use it when you need to check whether the datagram channel is already open
     * without triggering creation side-effects.  To open the channel, use
     * {@link #openDatagramChannel()}.
     * @return the current {@link QuicDatagramChannel}, or {@code null} if not yet opened
     */
    public QuicDatagramChannel getDatagramChannel() {
        return this.asyncChannel().onlyGetDatagramChannel();
    }

    /**
     * Asynchronously opens (or returns) the DATAGRAM channel for this connection.
     * <p>On the first call the channel is created with its own independent pipeline,
     * initialized using the connection's default {@link ProtoInitializer} (the same one
     * used when binding or connecting). Subsequent calls return a {@link Future} that
     * resolves to the same instance.
     * <p>If DATAGRAM is not supported by the connection (either side did not advertise
     * {@code max_datagram_frame_size}), or if it is administratively disabled via
     * {@link QuicSoConfig#setDisableDatagram}, this method throws synchronously.
     * @return a {@link Future} that completes with the {@link QuicDatagramChannel}, never {@code null}
     * @throws IllegalStateException if DATAGRAM is administratively disabled, or if the
     * negotiated {@code datagramFrameSize} is zero (neither side supports DATAGRAM)
     * @throws IOException if channel initialization fails
     */
    public Future<QuicDatagramChannel> openDatagramChannel() throws IOException {
        if (this.quicSoConfig.isDisableDatagram()) {
            throw new IllegalStateException("DATAGRAM is administratively disabled by QuicSoConfig.disableDatagram=true");
        }
        if (!isSupportDatagram()) {
            throw new IllegalStateException("DATAGRAM not supported: negotiated datagramFrameSize=0");
        }

        return this.asyncChannel().getOrCreateDatagramChannel();
    }
}