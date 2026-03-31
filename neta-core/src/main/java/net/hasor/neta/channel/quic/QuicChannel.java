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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.udp.UdpChannel;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Post-handshake QUIC connection channel exposed to the application layer.
 * <p>This object is created only after {@link QuicAsyncChannelHandshake} completes connection establishment and
 * provides public APIs for stream creation, DATAGRAM access, connection-level flow-control adjustment, path probing,
 * and graceful or error-based shutdown.
 * <p><b>Its position inside the internal protocol stack is as follows:</b>
 * <pre>
 *   UDP socket
 *     v
 *   QuicChannelAsync   -- packet, ACK, loss, path, and CID management
 *     +-- QuicChannel           -- public connection handle
 *        +-- QuicStreamChannel*    (multiplexed reliable streams)
 *        +-- QuicDatagramChannel?  (optional RFC 9221 unreliable datagrams)
 * </pre>
 * <p>This channel does not parse packets directly; all packet processing remains the responsibility of
 * {@link QuicChannelAsync}.
 * @author 赵永春 (zyc@hasor.net)
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
     * Creates a QuicChannel from a completed handshake result and reads negotiated outputs from the given QuicChannelAsync.
     */
    QuicChannel(QuicChannelAsync connCh) throws Throwable {
        super(connCh.getChannelId(), new QuicMonitor(connCh), connCh.getForListen(), connCh.getInitializer(), connCh, connCh.getContext());
        this.quicSoConfig = connCh.getSoConfig();
        this.clientMode = connCh.isClientMode();
        this.nextBidiStreamId = new AtomicLong(this.clientMode ? 0 : 1);
        this.nextUniStreamId = new AtomicLong(this.clientMode ? 2 : 3);
        this.localMaxStreamsBidi = new AtomicLong(this.quicSoConfig.getTpInitialMaxStreamsBidi());
        this.localMaxStreamsUni = new AtomicLong(this.quicSoConfig.getTpInitialMaxStreamsUni());

        // Build QuicSslContext from the handshake result.
        QuicAsyncChannelHandshake handshake = connCh.getHandshake();
        QuicTlsEngine tlsEngine = (handshake != null) ? handshake.getTlsEngine() : null;
        if (tlsEngine != null) {
            SslCertConfig certConfig = this.quicSoConfig.getSslConfig();
            String negotiatedAlpn = tlsEngine.getNegotiatedAlpn();
            // Prefer the SNI host name from TLS ClientHello and fall back to the socket address when absent.
            String sniHost = tlsEngine.getPeerSniHost();
            SocketAddress remote = connCh.getRemoteAddress();
            String peerHost = (sniHost != null && !sniHost.isEmpty()) ? sniHost : null;
            int peerPort = 0;
            if (remote instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) remote;
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

    /**
     * Returns the {@link SslContext} associated with the current QUIC connection; returns {@code null} when SSL is disabled.
     */
    public SslContext getSslContext() {
        return this.sslContext;
    }

    public QuicMonitor getQuicMonitor() {
        return (QuicMonitor) this.getMonitor();
    }

    /**
     * Returns the set of stream IDs that are still open, for protocol-level tracking.
     */
    public Set<Long> getOpenStreams() {
        return this.asyncChannel().getStreamIds();
    }

    /**
     * Returns the current connection-level data limit; this value can only be increased through {@link #sendMaxDataSize(long)}.
     */
    public long getMaxDataSize() {
        return this.asyncChannel().getConnectionMaxData();
    }

    /**
     * Sends a MAX_DATA frame (RFC 9000 §19.9) to raise the connection-level flow-control limit; the new value must be
     * greater than or equal to the current value.
     */
    public Future<QuicChannel> sendMaxDataSize(long newMaxDataSize) {
        long currentDataSize = this.asyncChannel().getConnectionMaxData();
        if (newMaxDataSize < currentDataSize) {
            throw new IllegalArgumentException("maxDataSize can only increase: current=" + currentDataSize + ", requested=" + newMaxDataSize);
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_DATA);
            byte[] valBytes = QuicVarInt.encode(newMaxDataSize);
            frame = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(typeBytes.length + valBytes.length);
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
     * Sends a PING frame and returns a future completed with RTT in milliseconds; equivalent to {@code ping(0)},
     * meaning no timeout is set.
     */
    public Future<Long> ping() {
        return this.asyncChannel().sendPingRtt(0);
    }

    /**
     * Sends a PING frame and returns a future completed with RTT in milliseconds; fails when timeoutMs is exceeded,
     * and 0 means no timeout.
     */
    public Future<Long> ping(long timeoutMs) {
        return this.asyncChannel().sendPingRtt(timeoutMs);
    }

    /**
     * Actively starts connection migration to a new network path (RFC 9000 §9) and returns a future containing the
     * measured path RTT.
     */
    public Future<Long> migrate() throws IOException {
        return this.asyncChannel().migrate();
    }

    /**
     * Sends a PATH_CHALLENGE frame carrying 8 bytes of challenge data; the peer should return a matching PATH_RESPONSE.
     */
    public Future<QuicChannel> pathChallenge(byte[] data) {
        if (data == null || data.length != 8) {
            throw new IllegalArgumentException("PATH_CHALLENGE data must be exactly 8 bytes");
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_CHALLENGE);
            frame = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(typeBytes.length + 8);
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
     * Sends a CONNECTION_CLOSE frame with the given error code and reason, then closes the connection; see
     * {@link QuicErrorCode} for error-code meanings.
     */
    public Future<QuicChannel> closeWithError(long errorCode, String reason) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        this.asyncChannel().closeWithError(errorCode, reason, future);
        return future;
    }

    /**
     * Gracefully closes the current QUIC connection by sending a CONNECTION_CLOSE frame carrying
     * {@link QuicErrorCode#NO_ERROR}.
     */
    public Future<QuicChannel> closeGracefully() {
        return closeWithError(QuicErrorCode.NO_ERROR, "");
    }

    /**
     * Returns the open {@link QuicStreamChannel} corresponding to the given stream ID (RFC 9000 §2.1); returns null
     * if none exists.
     */
    public QuicStreamChannel findStream(long streamId) {
        return this.asyncChannel().findStream(streamId);
    }

    // ── Bidi Stream ────────────────────────────────────────────────

    /**
     * Returns the maximum number of bidirectional streams the peer allows this endpoint to open.
     */
    public long getBidiMaxStreams() {
        return this.asyncChannel().getPeerMaxStreamsBidi();
    }

    /**
     * Asynchronously creates a new bidirectional stream, with the stream ID assigned automatically by the framework.
     */
    public Future<QuicStreamChannel> newBidiStream() {
        return this.asyncChannel().newStreamChannel(nextBidiStreamId());
    }

    /**
     * Computes the next bidirectional stream ID.
     */
    private long nextBidiStreamId() {
        long id = this.nextBidiStreamId.getAndAdd(4);
        long streamIndex = id / 4;
        long peerMax = this.asyncChannel().getPeerMaxStreamsBidi();
        if (streamIndex >= peerMax) {
            this.nextBidiStreamId.addAndGet(-4); // Roll back.
            throw new IllegalStateException("Bidirectional stream limit exceeded: " + peerMax);
        }

        return id;
    }

    /**
     * Sends a MAX_STREAMS (bidirectional) frame (RFC 9000 §19.11) and increases the bidirectional stream limit by
     * upgradeIncr.
     */
    public Future<QuicChannel> upgradeBidiStreams(long upgradeIncr) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            long newMax = this.localMaxStreamsBidi.addAndGet(upgradeIncr);
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_BIDI);
            byte[] valBytes = QuicVarInt.encode(newMax);
            frame = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(typeBytes.length + valBytes.length);
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

    /**
     * Returns the maximum number of unidirectional streams the peer allows this endpoint to open.
     */
    public long getUniMaxStreams() {
        return this.asyncChannel().getPeerMaxStreamsUni();
    }

    /**
     * Asynchronously creates a new unidirectional stream, with the stream ID assigned automatically by the framework.
     */
    public Future<QuicStreamChannel> newUniStream() {
        return this.asyncChannel().newStreamChannel(nextUniStreamId());
    }

    /**
     * Computes the next unidirectional stream ID.
     */
    private long nextUniStreamId() {
        long id = this.nextUniStreamId.getAndAdd(4);
        long streamIndex = id / 4;
        long peerMax = this.asyncChannel().getPeerMaxStreamsUni();
        if (streamIndex >= peerMax) {
            this.nextUniStreamId.addAndGet(-4); // Roll back.
            throw new IllegalStateException("Unidirectional stream limit exceeded: " + peerMax);
        }

        return id;
    }

    /**
     * Sends a MAX_STREAMS (unidirectional) frame (RFC 9000 §19.11) and increases the unidirectional stream limit by
     * upgradeIncr.
     */
    public Future<QuicChannel> upgradeUniStreams(long upgradeIncr) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            long newMax = this.localMaxStreamsUni.addAndGet(upgradeIncr);
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_UNI);
            byte[] valBytes = QuicVarInt.encode(newMax);
            frame = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(typeBytes.length + valBytes.length);
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
     * Returns whether the current connection supports DATAGRAM frames, which requires both sides to advertise a non-zero
     * max_datagram_frame_size.
     */
    public boolean isSupportDatagram() {
        return this.getDatagramFrameSize() > 0;
    }

    /**
     * Returns the negotiated maximum DATAGRAM frame payload size, taking the smaller of the local and peer limits;
     * returns 0 when unsupported.
     */
    public long getDatagramFrameSize() {
        return this.asyncChannel().getPeerDatagramMaxData();
    }

    /**
     * Returns the existing {@link QuicDatagramChannel}; returns null if it has not been opened yet and will not create
     * one automatically.
     */
    public QuicDatagramChannel getDatagramChannel() {
        return this.asyncChannel().onlyGetDatagramChannel();
    }

    /**
     * Asynchronously opens the DATAGRAM channel for the current connection, returning the existing one if it is already
     * present; throws when DATAGRAM is disabled or unsupported.
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