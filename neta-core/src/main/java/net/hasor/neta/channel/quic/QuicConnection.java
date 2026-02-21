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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetMonitor;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoContextService;

/**
 * Manages a single QUIC connection (one per remote peer).
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>QUIC handshake state machine (via {@link QuicTlsEngine} when SSL is enabled)</li>
 *   <li>Manages streams: {@code Map<Long, QuicStreamChannel>}</li>
 *   <li>Encrypts/decrypts QUIC packets (when SSL enabled)</li>
 *   <li>Sends QUIC STREAM frames via the underlying UDP {@link DatagramChannel}</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicConnection {
    private static final Logger logger = Logger.getLogger(QuicConnection.class);

    // ── Connection identity ────────────────────────────────────────────
    private final byte[]        srcConnectionId;
    private       byte[]        dstConnectionId;
    private final SocketAddress remoteAddress;
    private final SocketAddress localAddress;

    // ── State ──────────────────────────────────────────────────────────
    private final    AtomicBoolean  closed         = new AtomicBoolean(false);
    private final    AtomicLong     nextStreamId   = new AtomicLong(0);
    private volatile HandshakeState handshakeState = HandshakeState.INITIAL;

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

    // ── Streams ────────────────────────────────────────────────────────
    private final Set<Long> openStreams = ConcurrentHashMap.newKeySet();

    // ── Connection-level channel ───────────────────────────────────────
    private volatile QuicChannel quicChannel;

    // ── Underlying transport ───────────────────────────────────────────
    private final DatagramChannel  udpChannel;
    private final QuicSoConfig     soConfig;
    private final SoContextService context;
    private final QuicListen       listen;
    private final boolean          clientMode;
    private final ProtoInitializer clientInitializer;

    // ── QUIC version ───────────────────────────────────────────────────
    public static final int QUIC_VERSION_1 = 0x00000001;

    /** Handshake state machine */
    public enum HandshakeState {
        INITIAL,
        HANDSHAKE,
        ESTABLISHED,
        CLOSED
    }

    /**
     * Creates a new QUIC connection for a remote peer (server mode).
     */
    QuicConnection(byte[] srcConnId, SocketAddress remoteAddress, SocketAddress localAddress, DatagramChannel udpChannel, QuicSoConfig soConfig, SoContextService context, QuicListen listen) {
        this(srcConnId, remoteAddress, localAddress, udpChannel, soConfig, context, listen, false, null);
    }

    /**
     * Creates a new QUIC connection.
     * @param clientMode true for client-initiated connections
     * @param initializer protocol stack initializer (only used in client mode; server uses listen.getInitializer())
     */
    QuicConnection(byte[] srcConnId, SocketAddress remoteAddress, SocketAddress localAddress, DatagramChannel udpChannel, QuicSoConfig soConfig, SoContextService context, QuicListen listen, boolean clientMode, ProtoInitializer initializer) {
        this.srcConnectionId = srcConnId;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.udpChannel = udpChannel;
        this.soConfig = soConfig;
        this.sslEnabled = soConfig.isSslEnabled();
        this.context = context;
        this.listen = listen;
        this.clientMode = clientMode;
        this.clientInitializer = initializer;

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
                throw new RuntimeException("Failed to initialize QUIC TLS engine", e);
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    public boolean isOpen() {
        return !this.closed.get() && this.handshakeState != HandshakeState.CLOSED;
    }

    public HandshakeState getHandshakeState() {
        return this.handshakeState;
    }

    public byte[] getSrcConnectionId() {
        return this.srcConnectionId;
    }

    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    /** Returns the set of currently open stream IDs. */
    public Set<Long> getOpenStreams() {
        return this.openStreams;
    }

    /** Returns the connection-level {@link QuicChannel}, or null if not yet created. */
    public QuicChannel getQuicChannel() {
        return this.quicChannel;
    }

    /** Set the destination connection ID (used by client mode). */
    public void setDstConnectionId(byte[] dcid) {
        this.dstConnectionId = dcid;
    }

    /**
     * Process a received QUIC packet (may contain multiple frames).
     * @param data raw UDP datagram payload
     * @param offset data offset
     * @param length data length
     */
    public void processPacket(byte[] data, int offset, int length) {
        try {
            if (this.sslEnabled) {
                processEncryptedPacket(data, offset, length);
            } else {
                processRawPacket(data, offset, length);
            }
        } catch (Exception e) {
            logger.error("QUIC processPacket error from " + this.remoteAddress + ": " + e.getMessage());
        }
    }

    /**
     * Send STREAM frame data for a specific stream.
     * Auto-opens the stream (fires OPENED event) if not yet tracked.
     */
    public void sendStreamData(long streamId, byte[] payload, boolean fin) throws Exception {
        // Auto-open stream if first use
        if (this.openStreams.add(streamId)) {
            fireStreamEvent(streamId, true);
        }

        // Build STREAM frame
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

        sendApplicationData(frame);

        // If FIN, close the stream
        if (fin && this.openStreams.remove(streamId)) {
            fireStreamEvent(streamId, false);
        }
    }

    /**
     * Close a specific stream.
     * Fires a {@link QuicStreamEvent} CLOSED event if the stream was open.
     */
    public void closeStream(long streamId) {
        if (this.openStreams.remove(streamId)) {
            fireStreamEvent(streamId, false);
        }
    }

    /**
     * Close this QUIC connection and all its streams.
     */
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.handshakeState = HandshakeState.CLOSED;
            // Fire CLOSED event for all open streams
            for (Long streamId : this.openStreams) {
                fireStreamEvent(streamId, false);
            }
            this.openStreams.clear();
        }
    }

    // ── Connection-level channel management ────────────────────────────

    /**
     * Creates the connection-level {@link QuicChannel} if not already created.
     * Called when the QUIC handshake transitions to ESTABLISHED.
     */
    private synchronized void ensureChannelCreated() {
        if (this.quicChannel != null) {
            return;
        }
        try {
            long channelId = this.context.nextID();
            NetMonitor monitor = new NetMonitor();
            ProtoInitializer initializer = this.clientMode ? this.clientInitializer : this.listen.getInitializer();

            QuicAsyncConnectionChannel asyncChannel = new QuicAsyncConnectionChannel(channelId, this, this.soConfig, this.localAddress, this.remoteAddress);

            this.quicChannel = new QuicChannel(channelId, monitor, this.listen, initializer, asyncChannel, this.context, this);

            this.context.initChannel(this.quicChannel, true);
        } catch (Throwable e) {
            logger.error("Failed to create QUIC channel: " + e.getMessage(), e);
            this.quicChannel = null;
        }
    }

    /**
     * Send a client-side Initial packet to start the QUIC handshake (raw mode).
     */
    public void sendClientInitial() throws Exception {
        if (!this.clientMode) {
            throw new IllegalStateException("sendClientInitial() is only for client mode");
        }
        // Build a raw Initial packet (empty payload, just to trigger handshake)
        byte[] initialPayload = new byte[0];
        byte[] initialPacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], this.initialPn.getAndIncrement(), initialPayload);
        sendUdpDatagram(initialPacket);
    }

    /**
     * Fire a {@link QuicStreamEvent} on the connection-level channel.
     */
    private void fireStreamEvent(long streamId, boolean opened) {
        QuicChannel ch = this.quicChannel;
        if (ch != null && !ch.isClose()) {
            try {
                ch.fireUserEvent(QuicStreamEvent.class, new QuicStreamEvent(streamId, opened));
            } catch (Exception e) {
                logger.error("Failed to fire stream event for stream " + streamId + ": " + e.getMessage());
            }
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
            // Short header = 1-RTT application data
            processShortHeaderPacket(data, offset, length);
        }
    }

    private void processInitialPacket(byte[] data, int offset, QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.dstConnectionId == null) {
            this.dstConnectionId = parsed.scid;
        }

        // Decrypt
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

        // Parse CRYPTO frames in the payload
        processFrames(parsed.payload, true);

        // If handshake state is INITIAL and we've received ClientHello, respond
        if (this.handshakeState == HandshakeState.INITIAL && this.tlsEngine != null) {
            respondToClientHello(parsed);
        }
    }

    private void respondToClientHello(QuicPacket.ParsedPacket parsed) throws Exception {
        // ServerHello in Initial packet
        byte[] serverHello = this.tlsEngine.getServerHelloBytes();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, serverHello);
        byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
        byte[] initialPayload = concat(ackFrame, cryptoFrame);

        byte[] sKey = this.initialServerKeys[0];
        byte[] sIv = this.initialServerKeys[1];
        byte[] sHp = this.initialServerKeys[2];
        long pn = this.initialPn.getAndIncrement();

        byte[] initialPacket = QuicPacket.buildLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], pn, initialPayload, sKey, sIv, sHp, 1200);

        // Handshake messages in Handshake packet
        this.handshakeClientKeys = this.tlsEngine.getClientHandshakeKeys();
        this.handshakeServerKeys = this.tlsEngine.getServerHandshakeKeys();

        byte[] handshakeMessages = this.tlsEngine.getHandshakeBytes();
        byte[] hsCryptoFrame = QuicPacket.buildCryptoFrame(0, handshakeMessages);

        byte[] hsKey = this.handshakeServerKeys[0];
        byte[] hsIv = this.handshakeServerKeys[1];
        byte[] hsHp = this.handshakeServerKeys[2];
        long hsPn = this.handshakePn.getAndIncrement();

        byte[] handshakePacket = QuicPacket.buildLongHeaderPacket(QuicPacket.TYPE_HANDSHAKE, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, null, hsPn, hsCryptoFrame, hsKey, hsIv, hsHp, 0);

        // Coalesce and send
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

        // Process CRYPTO frames (client Finished)
        processFrames(parsed.payload, false);

        // Transition to ESTABLISHED
        if (this.handshakeState == HandshakeState.HANDSHAKE) {
            this.appClientKeys = this.tlsEngine.getClientAppKeys();
            this.appServerKeys = this.tlsEngine.getServerAppKeys();

            // Send HANDSHAKE_DONE in a 1-RTT packet
            byte[] hdFrame = QuicPacket.buildHandshakeDoneFrame();
            sendApplicationData(hdFrame);

            this.handshakeState = HandshakeState.ESTABLISHED;
            ensureChannelCreated();
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
                    // In raw mode, Initial packet means "connection start"
                    if (this.handshakeState == HandshakeState.INITIAL) {
                        if (!this.clientMode) {
                            // Server: send back a raw Initial ACK
                            byte[] ackFrame = QuicPacket.buildAckFrame(parsed.packetNumber, parsed.packetNumber);
                            byte[] responsePacket = QuicPacket.buildRawLongHeaderPacket(QuicPacket.TYPE_INITIAL, QUIC_VERSION_1, this.dstConnectionId, this.srcConnectionId, new byte[0], this.initialPn.getAndIncrement(), ackFrame);
                            sendUdpDatagram(responsePacket);
                        }

                        // Immediately go to ESTABLISHED
                        this.handshakeState = HandshakeState.ESTABLISHED;
                        ensureChannelCreated();
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
            // Short header in raw mode — just read payload (1 byte flags + dcid len)
            int dcidLen = this.srcConnectionId.length;
            int headerLen = 1 + dcidLen;
            // pn length from first byte
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
                // Skip ACK frame fields
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

            // Unknown frame — skip rest
            break;
        }
    }

    private void processApplicationFrames(byte[] payload) {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];

            if (frameType == QuicFrameType.PADDING) {
                continue;
            }
            if (frameType == QuicFrameType.PING) {
                continue;
            }
            if (frameType == QuicFrameType.HANDSHAKE_DONE) {
                continue;
            }
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                // Skip ACK fields
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

            // STREAM frames (0x08-0x0F)
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

                // Deliver the data to the stream channel
                deliverStreamData(streamId, streamData, hasFin);
                continue;
            }

            // Unknown frame — skip remaining
            break;
        }
    }

    private void deliverStreamData(long streamId, byte[] data, boolean fin) {
        // Auto-open stream if first time seen
        if (this.openStreams.add(streamId)) {
            fireStreamEvent(streamId, true);
        }

        // Deliver data to the connection pipeline (as ByteBuf)
        if (data.length > 0 && this.quicChannel != null) {
            this.context.notifyRcvChannelData(this.quicChannel.getChannelId(), ByteBuf.wrap(data));
        }

        // If FIN, close the stream
        if (fin && this.openStreams.remove(streamId)) {
            fireStreamEvent(streamId, false);
        }
    }

    // ── Packet sending ─────────────────────────────────────────────────

    private void sendApplicationData(byte[] framePayload) throws Exception {
        if (this.sslEnabled) {
            byte[] key = this.appServerKeys[0];
            byte[] iv = this.appServerKeys[1];
            byte[] hp = this.appServerKeys[2];
            long pn = this.appPn.getAndIncrement();

            byte[] packet = QuicPacket.buildShortHeaderPacket(this.dstConnectionId, pn, framePayload, key, iv, hp);
            sendUdpDatagram(packet);
        } else {
            // Raw mode: simple short header
            int dcidLen = (this.dstConnectionId != null) ? this.dstConnectionId.length : 0;
            long pn = this.appPn.getAndIncrement();
            int pnLen = 1;  // minimal
            int headerLen = 1 + dcidLen + pnLen;
            byte[] packet = new byte[headerLen + framePayload.length];

            packet[0] = (byte) (0x40 | (pnLen - 1)); // short header, fixed bit
            if (this.dstConnectionId != null) {
                System.arraycopy(this.dstConnectionId, 0, packet, 1, dcidLen);
            }
            packet[1 + dcidLen] = (byte) (pn & 0xFF);
            System.arraycopy(framePayload, 0, packet, headerLen, framePayload.length);
            sendUdpDatagram(packet);
        }
    }

    private void sendUdpDatagram(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        this.udpChannel.send(buf, this.remoteAddress);
    }

    // ── Utility ────────────────────────────────────────────────────────

    static byte[] generateConnectionId(int length) {
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
