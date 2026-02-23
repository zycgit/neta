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
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpChannel;
import net.hasor.neta.channel.udp.UdpTransport;

/**
 * Connection-level QUIC channel, extending {@link UdpChannel}.
 * <p>
 * QUIC is built on top of UDP, so this channel inherits the UDP channel
 * infrastructure and adds QUIC-specific protocol logic:
 * <ul>
 *   <li>Handshake state machine (INITIAL→HANDSHAKE→ESTABLISHED→CLOSED)</li>
 *   <li>TLS 1.3 encryption/decryption (when SSL is enabled)</li>
 *   <li>QUIC packet parsing and building</li>
 *   <li>Stream management (multiplexed streams over a single connection)</li>
 *   <li>Connection ID management</li>
 * </ul>
 * <p>
 * Two stream creation modes are supported:
 * <ul>
 *   <li>{@link #newStream(long)} — <b>Inherit mode</b>: the stream inherits the
 *       connection's pipeline configuration.</li>
 *   <li>{@link #newStream(long, ProtoInitializer)} — <b>Custom mode</b>: the stream
 *       gets its own pipeline with full lifecycle management.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicStreamChannel
 * @see QuicStreamEvent
 */
public class QuicChannel extends UdpChannel {
    private static final Logger logger = Logger.getLogger(QuicChannel.class);

    // ── QUIC version ───────────────────────────────────────────────────
    public static final int QUIC_VERSION_1 = 0x00000001;

    /** Handshake state machine */
    public enum HandshakeState {
        INITIAL,
        HANDSHAKE,
        ESTABLISHED,
        CLOSED
    }

    // ── Connection identity ────────────────────────────────────────────
    private final byte[]        srcConnectionId;
    private       byte[]        dstConnectionId;
    private final SocketAddress remoteAddress;

    // ── State ──────────────────────────────────────────────────────────
    private volatile HandshakeState handshakeState = HandshakeState.INITIAL;
    private volatile boolean        activated      = false;

    // ── SSL/TLS ────────────────────────────────────────────────────────
    private final boolean       sslEnabled;
    private       QuicTlsEngine tlsEngine;

    // Encryption keys: [key, iv, hp] for each level
    private byte[][] initialClientKeys;
    private byte[][] initialServerKeys;
    private byte[][] handshakeClientKeys;
    private byte[][] handshakeServerKeys;
    private byte[][] appClientKeys;
    private byte[][] appServerKeys;

    // ── Packet numbers ─────────────────────────────────────────────────
    private final AtomicLong initialPn          = new AtomicLong(0);
    private final AtomicLong handshakePn        = new AtomicLong(0);
    private final AtomicLong appPn              = new AtomicLong(0);
    private       long       largestInitialPn   = -1;
    private       long       largestHandshakePn = -1;
    private       long       largestAppPn       = -1;

    // ── Protocol-level stream tracking ─────────────────────────────────
    private final Set<Long> openStreams = ConcurrentHashMap.newKeySet();

    // ── Flow control (connection level, RFC 9000 §4) ───────────────────
    private volatile long       localMaxData;       // max data we allow peer to send
    private volatile long       peerMaxData;        // max data peer allows us to send
    private final    AtomicLong dataSent     = new AtomicLong(0);
    private final    AtomicLong dataReceived = new AtomicLong(0);

    // ── Stream limits (RFC 9000 §4.6) ──────────────────────────────────
    private volatile long       peerMaxStreamsBidi;  // max bidi streams peer allows us to open
    private volatile long       peerMaxStreamsUni;    // max uni streams peer allows us to open
    private volatile long       localMaxStreamsBidi; // max bidi streams we allow peer to open
    private volatile long       localMaxStreamsUni;   // max uni streams we allow peer to open
    private final    AtomicLong nextClientBidiStreamId = new AtomicLong(0);  // 0, 4, 8, ...
    private final    AtomicLong nextServerBidiStreamId = new AtomicLong(1);  // 1, 5, 9, ...
    private final    AtomicLong nextClientUniStreamId  = new AtomicLong(2);  // 2, 6, 10, ...
    private final    AtomicLong nextServerUniStreamId  = new AtomicLong(3);  // 3, 7, 11, ...

    // ── DATAGRAM support (RFC 9221) ────────────────────────────────────
    private volatile QuicStreamChannel datagramChannel;

    // ── Transport ──────────────────────────────────────────────────────
    private final DatagramChannel udpChannel;
    private final UdpTransport    ownedTransport; // non-null for client-mode channels; null for server-side
    private final QuicSoConfig    quicSoConfig;
    private final boolean         clientMode;

    // ── Stream management ──────────────────────────────────────────────
    private final ProtoInitializer                           defaultStreamInitializer;
    private final ConcurrentHashMap<Long, QuicStreamChannel> streams = new ConcurrentHashMap<>();

    // ── Constructors (package-private) ──────────────────────────────────

    /** Server mode: incoming connection from a remote peer. */
    QuicChannel(byte[] srcConnId, SocketAddress remoteAddr, SocketAddress localAddr, DatagramChannel udpChannel, QuicSoConfig soConfig, SoContextService context, QuicListen listen) throws IOException {
        this(context.nextID(), listen, listen.getInitializer(), srcConnId, remoteAddr, localAddr, udpChannel, null, soConfig, context, false);
    }

    /** Client mode: outgoing connection. Caller is responsible for closing the UDP channel. */
    QuicChannel(byte[] srcConnId, SocketAddress remoteAddr, SocketAddress localAddr, DatagramChannel udpChannel, QuicSoConfig soConfig, SoContextService context, ProtoInitializer initializer) throws IOException {
        this(context.nextID(), null, initializer, srcConnId, remoteAddr, localAddr, udpChannel, null, soConfig, context, true);
    }

    /** Client mode: takes ownership of the given {@link UdpTransport} (closed when this channel closes). */
    QuicChannel(byte[] srcConnId, SocketAddress remoteAddr, SocketAddress localAddr, UdpTransport transport, QuicSoConfig soConfig, SoContextService context, ProtoInitializer initializer) throws IOException {
        this(context.nextID(), null, initializer, srcConnId, remoteAddr, localAddr, transport.getChannel(), transport, soConfig, context, true);
    }

    private QuicChannel(long channelId, NetListen forListen, ProtoInitializer initializer, byte[] srcConnId, SocketAddress remoteAddr, SocketAddress localAddr, DatagramChannel udpChannel, UdpTransport ownedTransport, QuicSoConfig soConfig, SoContextService context, boolean clientMode) throws IOException {
        super(channelId, new NetMonitor(), forListen, initializer, new QuicAsyncConnectionChannel(channelId, soConfig, localAddr, remoteAddr, context), context);
        this.srcConnectionId = srcConnId;
        this.remoteAddress = remoteAddr;
        this.defaultStreamInitializer = initializer;
        this.udpChannel = udpChannel;
        this.ownedTransport = ownedTransport;
        this.quicSoConfig = soConfig;
        this.sslEnabled = soConfig.isSslEnabled();
        this.clientMode = clientMode;
        ((QuicAsyncConnectionChannel) this.asyncChannel).setQuicChannel(this);

        // Initialize flow control from transport parameters
        QuicSettings tp = soConfig.getTransportParams();
        this.localMaxData = tp.initialMaxData();
        this.peerMaxData = tp.initialMaxData();
        this.localMaxStreamsBidi = tp.initialMaxStreamsBidi();
        this.localMaxStreamsUni = tp.initialMaxStreamsUni();
        this.peerMaxStreamsBidi = tp.initialMaxStreamsBidi();
        this.peerMaxStreamsUni = tp.initialMaxStreamsUni();

        // In client mode, generate a random DCID for the Initial packet
        if (clientMode) {
            this.dstConnectionId = generateConnectionId(soConfig.getConnectionIdLength());
        }

        if (this.sslEnabled && !clientMode) {
            try {
                this.tlsEngine = new QuicTlsEngine(soConfig.getCertChain(), soConfig.getPrivateKey(), soConfig.getTransportParams());
                // Derive initial keys from the client's DCID (which is our SCID)
                byte[][] initialSecrets = QuicCrypto.deriveInitialSecrets(srcConnId);
                this.initialClientKeys = QuicCrypto.derivePacketKeys(initialSecrets[0]);
                this.initialServerKeys = QuicCrypto.derivePacketKeys(initialSecrets[1]);
            } catch (Exception e) {
                throw new IOException("Failed to initialize QUIC TLS engine", e);
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** Returns {@code true} if the QUIC connection is open (not closed and not in CLOSED state). */
    public boolean isConnectionOpen() {
        return this.handshakeState != HandshakeState.CLOSED;
    }

    public HandshakeState getHandshakeState() {
        return this.handshakeState;
    }

    public byte[] getSrcConnectionId() {
        return this.srcConnectionId;
    }

    /** Set the destination connection ID (used by client mode). */
    public void setDstConnectionId(byte[] dcid) {
        this.dstConnectionId = dcid;
    }

    /** Returns the set of currently open stream IDs (protocol-level tracking). */
    public Set<Long> getOpenStreams() {
        return this.openStreams;
    }

    // ── Flow control API (RFC 9000 §4) ─────────────────────────────────

    /** Returns the maximum amount of data the peer is allowed to send to us. */
    public long getLocalMaxData() {
        return this.localMaxData;
    }

    /** Returns the maximum amount of data we are allowed to send to the peer. */
    public long getPeerMaxData() {
        return this.peerMaxData;
    }

    /** Returns the total bytes sent at the connection level. */
    public long getDataSent() {
        return this.dataSent.get();
    }

    /** Returns the total bytes received at the connection level. */
    public long getDataReceived() {
        return this.dataReceived.get();
    }

    /**
     * Sends a MAX_DATA frame to the peer, increasing the connection-level flow control limit.
     * @param maxData the new maximum data limit
     */
    public void sendMaxData(long maxData) throws Exception {
        this.localMaxData = maxData;
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_DATA);
        byte[] valBytes = QuicVarInt.encode(maxData);
        byte[] frame = concat(typeBytes, valBytes);
        sendApplicationData(frame);
    }

    /**
     * Sends a MAX_STREAM_DATA frame to increase the flow control limit for a specific stream.
     * @param streamId the stream to update
     * @param maxStreamData the new maximum stream data limit
     */
    public void sendMaxStreamData(long streamId, long maxStreamData) throws Exception {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAM_DATA);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] valBytes = QuicVarInt.encode(maxStreamData);
        byte[] frame = new byte[typeBytes.length + sidBytes.length + valBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(valBytes, 0, frame, pos, valBytes.length);
        sendApplicationData(frame);
    }

    // ── Keep-alive / Probe API ─────────────────────────────────────────

    /**
     * Sends a PING frame to keep the connection alive.
     * The peer must acknowledge receipt of this frame.
     */
    public void sendPing() throws Exception {
        byte[] frame = QuicVarInt.encode(QuicFrameType.PING);
        sendApplicationData(frame);
    }

    /**
     * Sends a PATH_CHALLENGE frame with the given 8-byte data.
     * The peer should respond with a PATH_RESPONSE containing the same data.
     * @param data exactly 8 bytes of challenge data
     */
    public void sendPathChallenge(byte[] data) throws Exception {
        if (data == null || data.length != 8) {
            throw new IllegalArgumentException("PATH_CHALLENGE data must be exactly 8 bytes");
        }
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_CHALLENGE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        sendApplicationData(frame);
    }

    // ── Stream management API (RFC 9000 §2.1, §4.6) ───────────────────

    /** Returns the maximum number of bidirectional streams the peer allows us to open. */
    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    /** Returns the maximum number of unidirectional streams the peer allows us to open. */
    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    /**
     * Sends a MAX_STREAMS frame to allow the peer to open more bidirectional streams.
     * @param maxStreams new maximum number of bidirectional streams
     */
    public void sendMaxStreamsBidi(long maxStreams) throws Exception {
        this.localMaxStreamsBidi = maxStreams;
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_BIDI);
        byte[] valBytes = QuicVarInt.encode(maxStreams);
        byte[] frame = concat(typeBytes, valBytes);
        sendApplicationData(frame);
    }

    /**
     * Sends a MAX_STREAMS frame to allow the peer to open more unidirectional streams.
     * @param maxStreams new maximum number of unidirectional streams
     */
    public void sendMaxStreamsUni(long maxStreams) throws Exception {
        this.localMaxStreamsUni = maxStreams;
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAMS_UNI);
        byte[] valBytes = QuicVarInt.encode(maxStreams);
        byte[] frame = concat(typeBytes, valBytes);
        sendApplicationData(frame);
    }

    /**
     * Allocates the next bidirectional stream ID for the local endpoint.
     * Client-initiated: 0, 4, 8, ...  Server-initiated: 1, 5, 9, ...
     * @throws IllegalStateException if the stream limit is exceeded
     */
    public long nextBidiStreamId() {
        AtomicLong counter = this.clientMode ? this.nextClientBidiStreamId : this.nextServerBidiStreamId;
        long id = counter.getAndAdd(4);
        long streamIndex = id / 4;
        if (streamIndex >= this.peerMaxStreamsBidi) {
            counter.addAndGet(-4); // rollback
            throw new IllegalStateException("Bidirectional stream limit exceeded: " + this.peerMaxStreamsBidi);
        }
        return id;
    }

    /**
     * Allocates the next unidirectional stream ID for the local endpoint.
     * Client-initiated: 2, 6, 10, ...  Server-initiated: 3, 7, 11, ...
     * @throws IllegalStateException if the stream limit is exceeded
     */
    public long nextUniStreamId() {
        AtomicLong counter = this.clientMode ? this.nextClientUniStreamId : this.nextServerUniStreamId;
        long id = counter.getAndAdd(4);
        long streamIndex = id / 4;
        if (streamIndex >= this.peerMaxStreamsUni) {
            counter.addAndGet(-4); // rollback
            throw new IllegalStateException("Unidirectional stream limit exceeded: " + this.peerMaxStreamsUni);
        }
        return id;
    }

    /**
     * Sends a RESET_STREAM frame to abruptly terminate a stream.
     * @param streamId the stream to reset
     * @param errorCode application error code
     * @param finalSize the total number of bytes sent on this stream before reset
     */
    public void sendResetStream(long streamId, long errorCode, long finalSize) throws Exception {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.RESET_STREAM);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] errBytes = QuicVarInt.encode(errorCode);
        byte[] sizeBytes = QuicVarInt.encode(finalSize);
        byte[] frame = new byte[typeBytes.length + sidBytes.length + errBytes.length + sizeBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(errBytes, 0, frame, pos, errBytes.length);
        pos += errBytes.length;
        System.arraycopy(sizeBytes, 0, frame, pos, sizeBytes.length);
        sendApplicationData(frame);
    }

    /**
     * Sends a STOP_SENDING frame to request that the peer stop sending on a stream.
     * @param streamId the stream to stop receiving on
     * @param errorCode application error code
     */
    public void sendStopSending(long streamId, long errorCode) throws Exception {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.STOP_SENDING);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] errBytes = QuicVarInt.encode(errorCode);
        byte[] frame = new byte[typeBytes.length + sidBytes.length + errBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(errBytes, 0, frame, pos, errBytes.length);
        sendApplicationData(frame);
    }

    /**
     * Sends a CONNECTION_CLOSE frame to close the connection.
     * @param errorCode transport error code
     * @param reason human-readable reason phrase
     */
    public void sendConnectionClose(long errorCode, String reason) throws Exception {
        byte[] reasonBytes = (reason != null) ? reason.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.CONNECTION_CLOSE);
        byte[] errBytes = QuicVarInt.encode(errorCode);
        byte[] frameTypeBytes = QuicVarInt.encode(0); // frame type that triggered the error (0 = unknown)
        byte[] reasonLenBytes = QuicVarInt.encode(reasonBytes.length);
        byte[] frame = new byte[typeBytes.length + errBytes.length + frameTypeBytes.length + reasonLenBytes.length + reasonBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(errBytes, 0, frame, pos, errBytes.length);
        pos += errBytes.length;
        System.arraycopy(frameTypeBytes, 0, frame, pos, frameTypeBytes.length);
        pos += frameTypeBytes.length;
        System.arraycopy(reasonLenBytes, 0, frame, pos, reasonLenBytes.length);
        pos += reasonLenBytes.length;
        System.arraycopy(reasonBytes, 0, frame, pos, reasonBytes.length);
        sendApplicationData(frame);
    }

    // ── DATAGRAM API (RFC 9221) ────────────────────────────────────────

    /**
     * Creates or returns the DATAGRAM channel for this connection.
     * <p>
     * DATAGRAM channels use a special stream ID ({@link QuicStreamChannel#DATAGRAM_STREAM_ID})
     * and are not bound to any QUIC stream. Use {@link QuicStreamChannel#isDatagram()} to
     * check if a stream channel is a DATAGRAM channel.
     * @return the DATAGRAM channel
     */
    public QuicStreamChannel getOrCreateDatagramChannel() throws IOException {
        if (this.datagramChannel != null) {
            return this.datagramChannel;
        }
        synchronized (this) {
            if (this.datagramChannel != null) {
                return this.datagramChannel;
            }
            try {
                long channelId = this.soContext.nextID();
                NetMonitor monitor = new NetMonitor();
                QuicAsyncStreamChannel asyncCh = new QuicAsyncStreamChannel(//
                        channelId,                          //
                        QuicStreamChannel.DATAGRAM_STREAM_ID,//
                        this,                               //
                        this.asyncChannel.getSoConfig(),    //
                        this.asyncChannel.getLocalAddress(),//
                        this.asyncChannel.getRemoteAddress(),//
                        this.soContext);
                QuicStreamChannel dgCh = new QuicStreamChannel(//
                        channelId,                          //
                        QuicStreamChannel.DATAGRAM_STREAM_ID,//
                        monitor,                            //
                        this.forListen,                     //
                        this.defaultStreamInitializer,      //
                        asyncCh,                            //
                        this.soContext,                      //
                        this);
                this.soContext.initChannel(dgCh, false);
                this.datagramChannel = dgCh;
                return dgCh;
            } catch (Throwable e) {
                throw new IOException("Failed to create datagram channel", e);
            }
        }
    }

    /**
     * Sends a DATAGRAM frame with the given payload (RFC 9221).
     * @param payload the datagram payload data
     * @return the number of bytes written to the UDP transport
     */
    public int sendDatagram(byte[] payload) throws Exception {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.DATAGRAM_LEN);
        byte[] lenBytes = QuicVarInt.encode(payload.length);
        byte[] frame = new byte[typeBytes.length + lenBytes.length + payload.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
        pos += lenBytes.length;
        System.arraycopy(payload, 0, frame, pos, payload.length);
        return sendApplicationData(frame);
    }

    // ── Stream creation ────────────────────────────────────────────────

    /**
     * Creates a new stream channel that <strong>inherits</strong> the connection's
     * default pipeline configuration.
     */
    public QuicStreamChannel newStream(long streamId) throws IOException {
        return newStreamLocked(streamId, this.defaultStreamInitializer, false);
    }

    /**
     * Creates a new stream channel with a <strong>custom</strong> pipeline.
     */
    public QuicStreamChannel newStream(long streamId, ProtoInitializer initializer) throws IOException {
        return newStreamLocked(streamId, initializer, true);
    }

    /**
     * Returns the {@link QuicStreamChannel} for the given stream ID, or {@code null} if none exists.
     */
    public QuicStreamChannel findStream(long streamId) {
        return this.streams.get(streamId);
    }

    /**
     * Returns an unmodifiable view of all active stream channels.
     */
    public Collection<QuicStreamChannel> getStreams() {
        return Collections.unmodifiableCollection(this.streams.values());
    }

    // ── Internal: get-or-create (inherit mode) ─────────────────────────

    QuicStreamChannel getOrNewStream(long streamId) {
        QuicStreamChannel existing = this.streams.get(streamId);
        if (existing != null) {
            return existing;
        }
        synchronized (this.streams) {
            existing = this.streams.get(streamId);
            if (existing != null) {
                return existing;
            }

            try {
                return createStream(streamId, this.defaultStreamInitializer, false);
            } catch (Throwable e) {
                logger.error("Failed to create stream channel for stream " + streamId + ": " + e.getMessage(), e);
                return null;
            }
        }
    }

    private QuicStreamChannel createStream(long streamId, ProtoInitializer initializer, boolean initLifecycle) throws Throwable {
        long channelId = this.soContext.nextID();
        NetMonitor monitor = new NetMonitor();

        QuicAsyncStreamChannel asyncCh = new QuicAsyncStreamChannel(//
                channelId,                          //
                streamId,                           //
                this,                               //
                this.asyncChannel.getSoConfig(),    //
                this.asyncChannel.getLocalAddress(),//
                this.asyncChannel.getRemoteAddress(),//
                this.soContext);
        QuicStreamChannel streamCh = new QuicStreamChannel(//
                channelId,                          //
                streamId,                           //
                monitor,                            //
                this.forListen,                     //
                initializer,                        //
                asyncCh,                            //
                this.soContext,                      //
                this);

        this.soContext.initChannel(streamCh, initLifecycle);
        this.streams.put(streamId, streamCh);

        // Fire OPENED event on the connection-level channel
        try {
            this.fireUserEvent(QuicStreamEvent.class, new QuicStreamEvent(streamId, true));
        } catch (Exception e) {
            logger.error("Failed to fire stream OPENED event for stream " + streamId + ": " + e.getMessage());
        }

        return streamCh;
    }

    private QuicStreamChannel newStreamLocked(long streamId, ProtoInitializer initializer, boolean initLifecycle) throws IOException {
        synchronized (this.streams) {
            if (this.streams.containsKey(streamId)) {
                throw new IllegalStateException("Stream " + streamId + " already exists");
            }
            try {
                return createStream(streamId, initializer, initLifecycle);
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("Failed to create stream " + streamId, e);
            }
        }
    }

    // ── Stream removal ─────────────────────────────────────────────────

    public void closeStream(long streamId) {
        removeStream(streamId);
    }

    void removeStream(long streamId) {
        this.openStreams.remove(streamId);
        QuicStreamChannel removed = this.streams.remove(streamId);
        if (removed != null) {
            try {
                this.fireUserEvent(QuicStreamEvent.class, new QuicStreamEvent(streamId, false));
            } catch (Exception e) {
                logger.error("Failed to fire stream CLOSED event for stream " + streamId + ": " + e.getMessage());
            }
        }
    }

    // ── Packet processing ──────────────────────────────────────────────

    public void processPacket(byte[] data, int offset, int length) {
        try {
            if (this.sslEnabled) {
                processEncryptedPacket(data, offset, length);
            } else {
                processRawPacket(data, offset, length);
            }
        } catch (Exception e) {
            logger.error("QUIC processPacket error from " + this.remoteAddress + ": " + e.getMessage());
            this.soContext.notifyRcvChannelException(this.getChannelId(), false, new SoRcvException(e.getMessage(), e));
        }
    }

    // ── Encrypted packet processing (SSL mode) ────────────────────────

    private void processEncryptedPacket(byte[] data, int offset, int length) throws Exception {
        if (QuicPacket.isLongHeader(data)) {
            QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(data, offset, length);
            if (parsed == null) {
                return;
            }

            switch (parsed.packetType) {
                case QuicPacket.TYPE_INITIAL:
                    processInitialPacket(data, offset, parsed);
                    break;
                case QuicPacket.TYPE_HANDSHAKE:
                    processHandshakePacket(data, offset, parsed);
                    break;
                default:
                    logger.warn("Unsupported long header packet type: " + parsed.packetType);
                    break;
            }
        } else {
            processShortHeaderPacket(data, offset, length);
        }
    }

    private void processInitialPacket(byte[] data, int offset, QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.dstConnectionId == null) {
            this.dstConnectionId = parsed.scid;
        }

        byte[] key = this.initialClientKeys[0];
        byte[] iv = this.initialClientKeys[1];
        byte[] hp = this.initialClientKeys[2];
        if (!QuicPacket.decryptLongHeaderPacket(data, offset, parsed, key, iv, hp, this.largestInitialPn)) {
            logger.error("Failed to decrypt Initial packet");
            return;
        }
        if (parsed.packetNumber > this.largestInitialPn) {
            this.largestInitialPn = parsed.packetNumber;
        }

        processFrames(parsed.payload, true);

        if (this.handshakeState == HandshakeState.INITIAL && this.tlsEngine != null) {
            respondToClientHello(parsed);
        }
    }

    private void respondToClientHello(QuicPacket.ParsedPacket parsed) throws Exception {
        byte[] serverHello = this.tlsEngine.getServerHelloBytes();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, serverHello);
        byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
        byte[] initialPayload = concat(ackFrame, cryptoFrame);

        byte[] sKey = this.initialServerKeys[0];
        byte[] sIv = this.initialServerKeys[1];
        byte[] sHp = this.initialServerKeys[2];
        long pn = this.initialPn.getAndIncrement();

        byte[] initialPacket = QuicPacket.buildLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], pn, initialPayload, sKey, sIv, sHp, 1200);

        this.handshakeClientKeys = this.tlsEngine.getClientHandshakeKeys();
        this.handshakeServerKeys = this.tlsEngine.getServerHandshakeKeys();

        byte[] handshakeMessages = this.tlsEngine.getHandshakeBytes();
        byte[] hsCryptoFrame = QuicPacket.buildCryptoFrame(0, handshakeMessages);

        byte[] hsKey = this.handshakeServerKeys[0];
        byte[] hsIv = this.handshakeServerKeys[1];
        byte[] hsHp = this.handshakeServerKeys[2];
        long hsPn = this.handshakePn.getAndIncrement();

        byte[] handshakePacket = QuicPacket.buildLongHeaderPacket(QuicPacket.TYPE_HANDSHAKE, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, null, hsPn, hsCryptoFrame, hsKey, hsIv, hsHp, 0);

        byte[] coalesced = concat(initialPacket, handshakePacket);
        sendUdpDatagram(coalesced);

        this.handshakeState = HandshakeState.HANDSHAKE;
    }

    private void processHandshakePacket(byte[] data, int offset, QuicPacket.ParsedPacket parsed) throws Exception {
        byte[] key = this.handshakeClientKeys[0];
        byte[] iv = this.handshakeClientKeys[1];
        byte[] hp = this.handshakeClientKeys[2];

        if (!QuicPacket.decryptLongHeaderPacket(data, offset, parsed, key, iv, hp, this.largestHandshakePn)) {
            logger.error("Failed to decrypt Handshake packet");
            return;
        }
        if (parsed.packetNumber > this.largestHandshakePn) {
            this.largestHandshakePn = parsed.packetNumber;
        }

        processFrames(parsed.payload, false);

        if (this.handshakeState == HandshakeState.HANDSHAKE) {
            this.appClientKeys = this.tlsEngine.getClientAppKeys();
            this.appServerKeys = this.tlsEngine.getServerAppKeys();

            byte[] hdFrame = QuicPacket.buildHandshakeDoneFrame();
            sendApplicationData(hdFrame);

            this.handshakeState = HandshakeState.ESTABLISHED;
            activate();
            logger.info("QUIC handshake completed with " + this.remoteAddress);
        }
    }

    private void processShortHeaderPacket(byte[] data, int offset, int length) throws Exception {
        if (this.appClientKeys == null) {
            logger.warn("Received 1-RTT packet but no app keys available yet");
            return;
        }

        int dcidLen = this.srcConnectionId.length;
        byte[] key = this.appClientKeys[0];
        byte[] iv = this.appClientKeys[1];
        byte[] hp = this.appClientKeys[2];

        QuicPacket.ParsedPacket parsed = QuicPacket.decryptShortHeaderPacket(data, offset, length, dcidLen, key, iv, hp, this.largestAppPn);
        if (parsed == null) {
            logger.error("Failed to decrypt 1-RTT packet");
            return;
        }
        if (parsed.packetNumber > this.largestAppPn) {
            this.largestAppPn = parsed.packetNumber;
        }

        processApplicationFrames(parsed.payload);
    }

    // ── Raw packet processing (non-SSL mode) ──────────────────────────

    private void processRawPacket(byte[] data, int offset, int length) throws Exception {
        if (QuicPacket.isLongHeader(data)) {
            QuicPacket.ParsedPacket parsed = QuicPacket.parseRawLongHeaderPacket(data, offset, length);
            if (parsed == null) {
                return;
            }

            if (this.dstConnectionId == null) {
                this.dstConnectionId = parsed.scid;
            }

            switch (parsed.packetType) {
                case QuicPacket.TYPE_INITIAL:
                    if (this.handshakeState == HandshakeState.INITIAL) {
                        if (!this.clientMode) {
                            byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
                            byte[] responsePacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], this.initialPn.getAndIncrement(), ackFrame);
                            sendUdpDatagram(responsePacket);
                        }

                        this.handshakeState = HandshakeState.ESTABLISHED;
                        activate();
                        logger.info("QUIC raw handshake completed with " + this.remoteAddress);
                    }
                    if (parsed.payload != null && parsed.payload.length > 0) {
                        processApplicationFrames(parsed.payload);
                    }
                    break;

                case QuicPacket.TYPE_HANDSHAKE:
                    if (parsed.payload != null && parsed.payload.length > 0) {
                        processApplicationFrames(parsed.payload);
                    }
                    break;

                default:
                    break;
            }
        } else {
            int dcidLen = this.srcConnectionId.length;
            int headerLen = 1 + dcidLen;
            int pnLen = (data[offset] & 0x03) + 1;
            headerLen += pnLen;
            if (headerLen < length) {
                int payloadLen = length - headerLen;
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, offset + headerLen, payload, 0, payloadLen);
                processApplicationFrames(payload);
            }
        }
    }

    // ── Frame processing ───────────────────────────────────────────────

    private void processFrames(byte[] payload, boolean isInitial) throws Exception {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];

            if (frameType == QuicFrameType.PADDING) {
                pos++;
                continue;
            }
            if (frameType == QuicFrameType.PING) {
                pos += (int) typeResult[1];
                continue;
            }
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                pos += (int) typeResult[1];
                long[] largest = QuicVarInt.decode(payload, pos);
                pos += (int) largest[1];
                long[] delay = QuicVarInt.decode(payload, pos);
                pos += (int) delay[1];
                long[] count = QuicVarInt.decode(payload, pos);
                pos += (int) count[1];
                long[] firstRange = QuicVarInt.decode(payload, pos);
                pos += (int) firstRange[1];
                for (long i = 0; i < count[0]; i++) {
                    long[] gap = QuicVarInt.decode(payload, pos);
                    pos += (int) gap[1];
                    long[] ackRange = QuicVarInt.decode(payload, pos);
                    pos += (int) ackRange[1];
                }
                if (frameType == QuicFrameType.ACK_ECN) {
                    long[] ect0 = QuicVarInt.decode(payload, pos);
                    pos += (int) ect0[1];
                    long[] ect1 = QuicVarInt.decode(payload, pos);
                    pos += (int) ect1[1];
                    long[] ecnCe = QuicVarInt.decode(payload, pos);
                    pos += (int) ecnCe[1];
                }
                continue;
            }
            if (frameType == QuicFrameType.CRYPTO) {
                long[] cryptoResult = QuicPacket.parseCryptoFrame(payload, pos);
                if (cryptoResult != null) {
                    int dataStart = (int) cryptoResult[1];
                    int dataLen = (int) cryptoResult[2];
                    byte[] cryptoData = new byte[dataLen];
                    System.arraycopy(payload, dataStart, cryptoData, 0, dataLen);

                    if (this.tlsEngine != null) {
                        if (isInitial) {
                            this.tlsEngine.processClientHello(cryptoData);
                        } else {
                            this.tlsEngine.verifyClientFinished(cryptoData);
                        }
                    }

                    pos = dataStart + dataLen;
                } else {
                    break;
                }
                continue;
            }

            break;
        }
    }

    private void processApplicationFrames(byte[] payload) {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];

            // ── PADDING (0x00) ─────────────────────────────────────────
            if (frameType == QuicFrameType.PADDING) {
                continue;
            }
            // ── PING (0x01) ────────────────────────────────────────────
            if (frameType == QuicFrameType.PING) {
                continue;
            }
            // ── HANDSHAKE_DONE (0x1e) ──────────────────────────────────
            if (frameType == QuicFrameType.HANDSHAKE_DONE) {
                continue;
            }

            // ── ACK / ACK_ECN (0x02, 0x03) ─────────────────────────────
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                long[] largest = QuicVarInt.decode(payload, pos);
                pos += (int) largest[1];
                long[] delay = QuicVarInt.decode(payload, pos);
                pos += (int) delay[1];
                long[] count = QuicVarInt.decode(payload, pos);
                pos += (int) count[1];
                long[] firstRange = QuicVarInt.decode(payload, pos);
                pos += (int) firstRange[1];
                for (long i = 0; i < count[0]; i++) {
                    long[] gap = QuicVarInt.decode(payload, pos);
                    pos += (int) gap[1];
                    long[] ackRange = QuicVarInt.decode(payload, pos);
                    pos += (int) ackRange[1];
                }
                if (frameType == QuicFrameType.ACK_ECN) {
                    long[] ect0 = QuicVarInt.decode(payload, pos);
                    pos += (int) ect0[1];
                    long[] ect1 = QuicVarInt.decode(payload, pos);
                    pos += (int) ect1[1];
                    long[] ecnCe = QuicVarInt.decode(payload, pos);
                    pos += (int) ecnCe[1];
                }
                continue;
            }

            // ── RESET_STREAM (0x04) ────────────────────────────────────
            if (frameType == QuicFrameType.RESET_STREAM) {
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] errResult = QuicVarInt.decode(payload, pos);
                pos += (int) errResult[1];
                long[] sizeResult = QuicVarInt.decode(payload, pos);
                pos += (int) sizeResult[1];

                // Close the stream abruptly
                QuicStreamChannel streamCh = this.streams.get(streamId);
                if (streamCh != null) {
                    streamCh.setRcvFinReceived();
                    removeStream(streamId);
                }
                continue;
            }

            // ── STOP_SENDING (0x05) ────────────────────────────────────
            if (frameType == QuicFrameType.STOP_SENDING) {
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] errResult = QuicVarInt.decode(payload, pos);
                pos += (int) errResult[1];

                // Peer requests us to stop sending on this stream
                QuicStreamChannel streamCh = this.streams.get(streamId);
                if (streamCh != null) {
                    removeStream(streamId);
                }
                continue;
            }

            // ── NEW_TOKEN (0x07) ───────────────────────────────────────
            if (frameType == QuicFrameType.NEW_TOKEN) {
                long[] lenResult = QuicVarInt.decode(payload, pos);
                int tokenLen = (int) lenResult[0];
                pos += (int) lenResult[1];
                pos += tokenLen; // skip token data
                continue;
            }

            // ── STREAM frames (0x08-0x0F) ──────────────────────────────
            if (QuicFrameType.isStream(frameType)) {
                boolean hasFin = QuicFrameType.streamFin(frameType);
                boolean hasLen = QuicFrameType.streamLen(frameType);
                boolean hasOff = QuicFrameType.streamOff(frameType);

                long[] streamIdResult = QuicVarInt.decode(payload, pos);
                long streamId = streamIdResult[0];
                pos += (int) streamIdResult[1];

                long streamOffset = 0;
                if (hasOff) {
                    long[] offResult = QuicVarInt.decode(payload, pos);
                    streamOffset = offResult[0];
                    pos += (int) offResult[1];
                }

                int dataLen;
                if (hasLen) {
                    long[] lenResult = QuicVarInt.decode(payload, pos);
                    dataLen = (int) lenResult[0];
                    pos += (int) lenResult[1];
                } else {
                    dataLen = payload.length - pos;
                }

                byte[] streamData = new byte[dataLen];
                System.arraycopy(payload, pos, streamData, 0, dataLen);
                pos += dataLen;

                // Update connection-level flow control
                this.dataReceived.addAndGet(dataLen);

                deliverStreamData(streamId, streamData, hasFin);
                continue;
            }

            // ── MAX_DATA (0x10) ────────────────────────────────────────
            if (frameType == QuicFrameType.MAX_DATA) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                long newMaxData = valResult[0];
                pos += (int) valResult[1];
                // Peer increases the limit of data we can send
                if (newMaxData > this.peerMaxData) {
                    this.peerMaxData = newMaxData;
                }
                continue;
            }

            // ── MAX_STREAM_DATA (0x11) ─────────────────────────────────
            if (frameType == QuicFrameType.MAX_STREAM_DATA) {
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] valResult = QuicVarInt.decode(payload, pos);
                pos += (int) valResult[1];
                // Stream-level flow control update (logged, actual enforcement is best-effort)
                continue;
            }

            // ── MAX_STREAMS_BIDI (0x12) ────────────────────────────────
            if (frameType == QuicFrameType.MAX_STREAMS_BIDI) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                long newMax = valResult[0];
                pos += (int) valResult[1];
                if (newMax > this.peerMaxStreamsBidi) {
                    this.peerMaxStreamsBidi = newMax;
                }
                continue;
            }

            // ── MAX_STREAMS_UNI (0x13) ─────────────────────────────────
            if (frameType == QuicFrameType.MAX_STREAMS_UNI) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                long newMax = valResult[0];
                pos += (int) valResult[1];
                if (newMax > this.peerMaxStreamsUni) {
                    this.peerMaxStreamsUni = newMax;
                }
                continue;
            }

            // ── DATA_BLOCKED (0x14) ────────────────────────────────────
            if (frameType == QuicFrameType.DATA_BLOCKED) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                pos += (int) valResult[1];
                // Peer is blocked — consider sending MAX_DATA
                logger.info("Peer is DATA_BLOCKED at offset " + valResult[0]);
                continue;
            }

            // ── STREAM_DATA_BLOCKED (0x15) ─────────────────────────────
            if (frameType == QuicFrameType.STREAM_DATA_BLOCKED) {
                long[] sidResult = QuicVarInt.decode(payload, pos);
                pos += (int) sidResult[1];
                long[] valResult = QuicVarInt.decode(payload, pos);
                pos += (int) valResult[1];
                // Peer is blocked on a specific stream — consider sending MAX_STREAM_DATA
                continue;
            }

            // ── STREAMS_BLOCKED_BIDI (0x16) ────────────────────────────
            if (frameType == QuicFrameType.STREAMS_BLOCKED_BIDI) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                pos += (int) valResult[1];
                // Peer wants to open more bidi streams — consider sending MAX_STREAMS_BIDI
                continue;
            }

            // ── STREAMS_BLOCKED_UNI (0x17) ─────────────────────────────
            if (frameType == QuicFrameType.STREAMS_BLOCKED_UNI) {
                long[] valResult = QuicVarInt.decode(payload, pos);
                pos += (int) valResult[1];
                // Peer wants to open more uni streams — consider sending MAX_STREAMS_UNI
                continue;
            }

            // ── NEW_CONNECTION_ID (0x18) ───────────────────────────────
            if (frameType == QuicFrameType.NEW_CONNECTION_ID) {
                long[] seqResult = QuicVarInt.decode(payload, pos);
                pos += (int) seqResult[1];
                long[] retireResult = QuicVarInt.decode(payload, pos);
                pos += (int) retireResult[1];
                int cidLen = payload[pos] & 0xFF;
                pos += 1;
                pos += cidLen; // skip CID bytes
                pos += 16;    // skip Stateless Reset Token (16 bytes)
                continue;
            }

            // ── RETIRE_CONNECTION_ID (0x19) ────────────────────────────
            if (frameType == QuicFrameType.RETIRE_CONNECTION_ID) {
                long[] seqResult = QuicVarInt.decode(payload, pos);
                pos += (int) seqResult[1];
                continue;
            }

            // ── PATH_CHALLENGE (0x1a) ──────────────────────────────────
            if (frameType == QuicFrameType.PATH_CHALLENGE) {
                byte[] challengeData = new byte[8];
                System.arraycopy(payload, pos, challengeData, 0, 8);
                pos += 8;
                // Respond with PATH_RESPONSE containing the same 8 bytes
                try {
                    sendPathResponse(challengeData);
                } catch (Exception e) {
                    logger.error("Failed to send PATH_RESPONSE: " + e.getMessage());
                }
                continue;
            }

            // ── PATH_RESPONSE (0x1b) ───────────────────────────────────
            if (frameType == QuicFrameType.PATH_RESPONSE) {
                pos += 8; // skip 8-byte response data
                continue;
            }

            // ── CONNECTION_CLOSE (0x1c) ────────────────────────────────
            if (frameType == QuicFrameType.CONNECTION_CLOSE) {
                long[] errResult = QuicVarInt.decode(payload, pos);
                pos += (int) errResult[1];
                long[] ftResult = QuicVarInt.decode(payload, pos);
                pos += (int) ftResult[1];
                long[] reasonLenResult = QuicVarInt.decode(payload, pos);
                int reasonLen = (int) reasonLenResult[0];
                pos += (int) reasonLenResult[1];
                pos += reasonLen; // skip reason phrase
                this.handshakeState = HandshakeState.CLOSED;
                logger.info("Received CONNECTION_CLOSE from " + this.remoteAddress + " (error=" + errResult[0] + ")");
                continue;
            }

            // ── CONNECTION_CLOSE_APP (0x1d) ────────────────────────────
            if (frameType == QuicFrameType.CONNECTION_CLOSE_APP) {
                long[] errResult = QuicVarInt.decode(payload, pos);
                pos += (int) errResult[1];
                long[] reasonLenResult = QuicVarInt.decode(payload, pos);
                int reasonLen = (int) reasonLenResult[0];
                pos += (int) reasonLenResult[1];
                pos += reasonLen; // skip reason phrase
                this.handshakeState = HandshakeState.CLOSED;
                logger.info("Received CONNECTION_CLOSE(app) from " + this.remoteAddress + " (error=" + errResult[0] + ")");
                continue;
            }

            // ── DATAGRAM / DATAGRAM_LEN (0x30, 0x31) ──────────────────
            if (QuicFrameType.isDatagram(frameType)) {
                int dataLen;
                if (QuicFrameType.datagramHasLen(frameType)) {
                    long[] lenResult = QuicVarInt.decode(payload, pos);
                    dataLen = (int) lenResult[0];
                    pos += (int) lenResult[1];
                } else {
                    dataLen = payload.length - pos;
                }
                byte[] dgData = new byte[dataLen];
                System.arraycopy(payload, pos, dgData, 0, dataLen);
                pos += dataLen;

                deliverDatagramData(dgData);
                continue;
            }

            // Unknown frame type — skip remaining payload
            logger.warn("Unknown QUIC frame type 0x" + Integer.toHexString(frameType) + " — skipping remaining payload");
            break;
        }
    }

    private void deliverStreamData(long streamId, byte[] data, boolean fin) {
        QuicStreamChannel streamCh = getOrNewStream(streamId);
        if (streamCh == null) {
            return;
        }

        this.openStreams.add(streamId);

        if (fin) {
            streamCh.setRcvFinReceived();
        }

        streamCh.getMonitor().updateRcvCounter(data.length);
        this.soContext.notifyRcvChannelData(streamCh.getChannelId(), ByteBuf.wrap(data));

        if (fin) {
            this.openStreams.remove(streamId);
        }
    }

    private void deliverDatagramData(byte[] data) {
        try {
            QuicStreamChannel dgCh = getOrCreateDatagramChannel();
            dgCh.getMonitor().updateRcvCounter(data.length);
            this.soContext.notifyRcvChannelData(dgCh.getChannelId(), ByteBuf.wrap(data));
        } catch (Exception e) {
            logger.error("Failed to deliver DATAGRAM data: " + e.getMessage());
        }
    }

    private void sendPathResponse(byte[] data) throws Exception {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.PATH_RESPONSE);
        byte[] frame = new byte[typeBytes.length + 8];
        System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
        System.arraycopy(data, 0, frame, typeBytes.length, 8);
        sendApplicationData(frame);
    }

    // ── Packet sending ─────────────────────────────────────────────────

    public int sendStreamData(long streamId, byte[] payload, boolean fin) throws Exception {
        if (this.openStreams.add(streamId)) {
            getOrNewStream(streamId);
        }

        // Track connection-level flow control
        this.dataSent.addAndGet(payload.length);

        int frameType = QuicFrameType.STREAM_BASE | QuicFrameType.STREAM_LEN_BIT;
        if (fin) {
            frameType |= QuicFrameType.STREAM_FIN_BIT;
        }
        byte[] typeBytes = QuicVarInt.encode(frameType);
        byte[] sidBytes = QuicVarInt.encode(streamId);
        byte[] lenBytes = QuicVarInt.encode(payload.length);

        byte[] frame = new byte[typeBytes.length + sidBytes.length + lenBytes.length + payload.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
        pos += sidBytes.length;
        System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
        pos += lenBytes.length;
        System.arraycopy(payload, 0, frame, pos, payload.length);

        int written = sendApplicationData(frame);

        if (fin && written > 0) {
            this.openStreams.remove(streamId);
            removeStream(streamId);
        }
        return written;
    }

    public void sendClientInitial() throws Exception {
        if (!this.clientMode) {
            throw new IllegalStateException("sendClientInitial() is only for client mode");
        }
        byte[] initialPayload = new byte[0];
        byte[] initialPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], this.initialPn.getAndIncrement(), initialPayload);
        sendUdpDatagram(initialPacket);
    }

    int sendApplicationData(byte[] framePayload) throws Exception {
        if (this.sslEnabled) {
            byte[] key = this.appServerKeys[0];
            byte[] iv = this.appServerKeys[1];
            byte[] hp = this.appServerKeys[2];
            long pn = this.appPn.getAndIncrement();

            byte[] packet = QuicPacket.buildShortHeaderPacket(this.dstConnectionId, pn, framePayload, key, iv, hp);
            return sendUdpDatagram(packet);
        } else {
            int dcidLen = (this.dstConnectionId != null) ? this.dstConnectionId.length : 0;
            long pn = this.appPn.getAndIncrement();
            int pnLen = 1;
            int headerLen = 1 + dcidLen + pnLen;
            byte[] packet = new byte[headerLen + framePayload.length];

            packet[0] = (byte) (0x40 | (pnLen - 1));
            if (this.dstConnectionId != null) {
                System.arraycopy(this.dstConnectionId, 0, packet, 1, dcidLen);
            }
            packet[1 + dcidLen] = (byte) (pn & 0xFF);
            System.arraycopy(framePayload, 0, packet, headerLen, framePayload.length);
            return sendUdpDatagram(packet);
        }
    }

    int sendUdpDatagram(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        return this.udpChannel.send(buf, this.remoteAddress);
    }

    // ── Activation ─────────────────────────────────────────────────────

    private synchronized void activate() {
        if (this.activated) {
            return;
        }
        try {
            this.soContext.initChannel(this, true);
            this.activated = true;
        } catch (Throwable e) {
            logger.error("Failed to activate QUIC channel: " + e.getMessage(), e);
        }
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    @Override
    public Future<NetChannel> close() {
        this.handshakeState = HandshakeState.CLOSED;
        this.openStreams.clear();

        // Close datagram channel if present
        QuicStreamChannel dgCh = this.datagramChannel;
        if (dgCh != null) {
            try {
                dgCh.closeNow();
            } catch (Exception e) {
                logger.error("Error closing datagram channel: " + e.getMessage());
            }
            this.datagramChannel = null;
        }

        for (QuicStreamChannel streamCh : this.streams.values()) {
            try {
                streamCh.closeNow();
            } catch (Exception e) {
                logger.error("Error closing stream " + streamCh.getStreamId() + ": " + e.getMessage());
            }
        }
        this.streams.clear();

        IOUtils.closeQuietly(this.ownedTransport);

        return super.close();
    }

    @Override
    public void closeNow() {
        this.handshakeState = HandshakeState.CLOSED;
        this.openStreams.clear();

        // Close datagram channel if present
        QuicStreamChannel dgCh = this.datagramChannel;
        if (dgCh != null) {
            try {
                dgCh.closeNow();
            } catch (Exception e) {
                logger.error("Error closing datagram channel: " + e.getMessage());
            }
            this.datagramChannel = null;
        }

        for (QuicStreamChannel streamCh : this.streams.values()) {
            try {
                streamCh.closeNow();
            } catch (Exception e) {
                logger.error("Error closing stream " + streamCh.getStreamId() + ": " + e.getMessage());
            }
        }
        this.streams.clear();

        IOUtils.closeQuietly(this.ownedTransport);

        super.closeNow();
    }

    // ── Utility ────────────────────────────────────────────────────────

    public static byte[] generateConnectionId(int length) {
        byte[] cid = new byte[length];
        new SecureRandom().nextBytes(cid);
        return cid;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
