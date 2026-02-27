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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslCertHelper;

/**
 * Shared handshake logic for QUIC client and server channels.
 * <p>
 * Manages the TLS 1.3 / QUIC handshake state machine, connection IDs,
 * packet number tracking, and QUIC packet protection keys for all
 * encryption levels (Initial, Handshake, 1-RTT).
 * <p>
 * Both {@link QuicAsyncClientChannel} and {@link QuicAsyncServerChannel}
 * delegate handshake processing to this class.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncChannelHandshake {
    /** Encryption level constants for external callers (maps to internal enum). */
    static final         int             LEVEL_INITIAL                             = 0;
    static final         int             LEVEL_HANDSHAKE                           = 1;
    static final         int             LEVEL_APP                                 = 2;
    // ── Transport Parameter IDs (RFC 9000 §18.2, RFC 9221) ─────────────
    static final         int             PARAM_MAX_IDLE_TIMEOUT                    = 0x01;
    static final         int             PARAM_MAX_UDP_PAYLOAD_SIZE                = 0x03;
    static final         int             PARAM_INITIAL_MAX_DATA                    = 0x04;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL  = 0x05;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_UNI         = 0x07;
    static final         int             PARAM_INITIAL_MAX_STREAMS_BIDI            = 0x08;
    static final         int             PARAM_INITIAL_MAX_STREAMS_UNI             = 0x09;
    static final         int             PARAM_ACK_DELAY_EXPONENT                  = 0x0a;
    static final         int             PARAM_MAX_ACK_DELAY                       = 0x0b;
    static final         int             PARAM_ACTIVE_CONNECTION_ID_LIMIT          = 0x0e;
    /** RFC 9221: max_datagram_frame_size transport parameter. 0 = DATAGRAM not supported. */
    static final         int             PARAM_MAX_DATAGRAM_FRAME_SIZE             = 0x20;
    private static final Logger          logger                                    = Logger.getLogger(QuicAsyncChannelHandshake.class);
    // ── Identity ───────────────────────────────────────────────────────
    private final        boolean         clientMode;
    private final        QuicSoConfig    soConfig;
    private final        DatagramChannel udpChannel;
    private final        SocketAddress   remoteAddress;

    // ── Connection IDs (RFC 9000 §5.1) ─────────────────────────────────
    private final byte[]        localCid;   // our Connection ID
    // ── QUIC version ───────────────────────────────────────────────────
    private final QuicVersion   quicVersion;
    // ── Packet numbers (monotonic per encryption level) ────────────────
    private final AtomicLong    initialPacketNumber   = new AtomicLong(0);
    private final AtomicLong    handshakePacketNumber = new AtomicLong(0);
    private final AtomicLong    appPacketNumber       = new AtomicLong(0);
    // ── Largest received packet numbers (for ACK / PN decoding) ────────
    private final AtomicLong    largestInitialPn      = new AtomicLong(-1);
    private final AtomicLong    largestHandshakePn    = new AtomicLong(-1);
    private final AtomicLong    largestAppPn          = new AtomicLong(-1);
    /** Buffered 0-RTT packets received before handshake completes. */
    private final List<byte[]>  buffered0RttData      = new ArrayList<byte[]>();
    private       byte[]        remoteCid;  // peer's Connection ID (DCID when sending)
    // ── Packet protection keys [key, iv, hp] per level ─────────────────
    private       byte[][]      clientInitialKeys;
    private       byte[][]      serverInitialKeys;
    private       byte[][]      clientHandshakeKeys;
    private       byte[][]      serverHandshakeKeys;
    private       byte[][]      clientAppKeys;
    private       byte[][]      serverAppKeys;
    // ── TLS engine (null if sslEnabled=false) ──────────────────────────
    private       QuicTlsEngine tlsEngine;

    // ── 0-RTT buffering ────────────────────────────────────────────────
    // ── Handshake state ────────────────────────────────────────────────
    private volatile QuicAsyncHandshakeState state               = QuicAsyncHandshakeState.INITIAL;
    /** Key generation counter for key updates. Starts at 0 after handshake. */
    private          int                     keyUpdateGeneration = 0;

    // ── Getters ────────────────────────────────────────────────────────

    /**
     * Creates a new handshake handler.
     * @param clientMode {@code true} for client side, {@code false} for server side
     * @param soConfig QUIC configuration
     * @param udpChannel the underlying UDP channel for sending packets
     * @param remoteAddress the remote peer address
     */
    QuicAsyncChannelHandshake(boolean clientMode, QuicSoConfig soConfig,//
            DatagramChannel udpChannel, SocketAddress remoteAddress) {
        this.clientMode = clientMode;
        this.soConfig = soConfig;
        this.udpChannel = udpChannel;
        this.remoteAddress = remoteAddress;
        this.quicVersion = soConfig.getQuicVersion();

        // Generate local Connection ID
        int cidLen = soConfig.getConnectionIdLength();
        this.localCid = new byte[cidLen];
        new SecureRandom().nextBytes(this.localCid);
        this.remoteCid = new byte[0]; // will be set during handshake
    }

    /** Concatenates two byte arrays. */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** Maps LEVEL_* integer constants to internal enum values. */
    private static QuicAsyncHandshakeState levelToState(int level) {
        switch (level) {
            case LEVEL_INITIAL:
                return QuicAsyncHandshakeState.INITIAL;
            case LEVEL_HANDSHAKE:
                return QuicAsyncHandshakeState.HANDSHAKE;
            case LEVEL_APP:
                return QuicAsyncHandshakeState.ESTABLISHED;
            default:
                throw new IllegalArgumentException("Unknown level: " + level);
        }
    }

    public boolean isClientMode() {
        return this.clientMode;
    }

    public QuicAsyncHandshakeState getState() {
        return this.state;
    }

    public boolean isEstablished() {
        return this.state == QuicAsyncHandshakeState.ESTABLISHED;
    }

    public byte[] getLocalCid() {
        return this.localCid;
    }

    public byte[] getRemoteCid() {
        return this.remoteCid;
    }

    // ── Packet number management ───────────────────────────────────────

    public QuicVersion getQuicVersion() {
        return this.quicVersion;
    }

    public QuicTlsEngine getTlsEngine() {
        return this.tlsEngine;
    }

    public boolean isSslEnabled() {
        return this.soConfig.isSslEnabled();
    }

    public long nextInitialPacketNumber() {
        return this.initialPacketNumber.getAndIncrement();
    }

    public long nextHandshakePacketNumber() {
        return this.handshakePacketNumber.getAndIncrement();
    }

    public long nextAppPacketNumber() {
        return this.appPacketNumber.getAndIncrement();
    }

    public void updateLargestInitialPn(long pn) {
        this.largestInitialPn.updateAndGet(cur -> Math.max(cur, pn));
    }

    public void updateLargestHandshakePn(long pn) {
        this.largestHandshakePn.updateAndGet(cur -> Math.max(cur, pn));
    }

    public void updateLargestAppPn(long pn) {
        this.largestAppPn.updateAndGet(cur -> Math.max(cur, pn));
    }

    // ── Key accessors ──────────────────────────────────────────────────

    public long getLargestInitialPn() {
        return this.largestInitialPn.get();
    }

    public long getLargestHandshakePn() {
        return this.largestHandshakePn.get();
    }

    // ── Initialization ─────────────────────────────────────────────────

    public long getLargestAppPn() {
        return this.largestAppPn.get();
    }

    /** Returns the keys we use to SEND at the given level. client mode → client keys; server mode → server keys. */
    public byte[][] getSendKeys(QuicAsyncHandshakeState level) {
        switch (level) {
            case INITIAL:
                return this.clientMode ? this.clientInitialKeys : this.serverInitialKeys;
            case HANDSHAKE:
                return this.clientMode ? this.clientHandshakeKeys : this.serverHandshakeKeys;
            case ESTABLISHED:
                return this.clientMode ? this.clientAppKeys : this.serverAppKeys;
            default:
                return null;
        }
    }

    // ── Server-side handshake processing ───────────────────────────────

    /** Returns the keys we use to RECEIVE at the given level. client mode → server keys; server mode → client keys. */
    public byte[][] getRecvKeys(QuicAsyncHandshakeState level) {
        switch (level) {
            case INITIAL:
                return this.clientMode ? this.serverInitialKeys : this.clientInitialKeys;
            case HANDSHAKE:
                return this.clientMode ? this.serverHandshakeKeys : this.clientHandshakeKeys;
            case ESTABLISHED:
                return this.clientMode ? this.serverAppKeys : this.clientAppKeys;
            default:
                return null;
        }
    }

    /**
     * Derives Initial encryption keys from the given Destination Connection ID.
     * Must be called before any Initial packet can be sent or received.
     * @param originalDcid the DCID from the first Initial packet (client's chosen DCID)
     */
    public void deriveInitialKeys(byte[] originalDcid) throws Exception {
        byte[][] secrets = QuicCrypto.deriveInitialSecrets(originalDcid, this.quicVersion);
        this.clientInitialKeys = QuicCrypto.derivePacketKeys(secrets[0], this.quicVersion);
        this.serverInitialKeys = QuicCrypto.derivePacketKeys(secrets[1], this.quicVersion);
    }

    /**
     * Initializes the TLS engine. For server mode this creates {@link QuicTlsEngine}
     * with cert/key from {@link SslCertConfig}. For client mode the TLS engine will
     * be created during ClientHello generation (future work).
     */
    public void initTlsEngine() throws Exception {
        if (!this.soConfig.isSslEnabled()) {
            this.tlsEngine = null;
            return;
        }
        if (!this.clientMode) {
            // Server mode: resolve certificate chain and private key via SslCertHelper
            SslCertConfig certConfig = this.soConfig.getSslConfig();
            if (certConfig.getCertChainDirect() == null || certConfig.getPrivateKeyDirect() == null) {
                // Load from PEM/JKS files if direct values not set
                Object[] certAndKey = SslCertHelper.loadCertificateAndKey(certConfig);
                if (certAndKey[0] != null) {
                    certConfig.setCertChainDirect((X509Certificate[]) certAndKey[0]);
                }
                if (certAndKey[1] != null) {
                    certConfig.setPrivateKeyDirect((PrivateKey) certAndKey[1]);
                }
            }
            this.tlsEngine = new QuicTlsEngine(certConfig, this.soConfig, null);
        } else {
            // Client mode: create TLS engine for client handshake
            SslCertConfig certConfig = this.soConfig.getSslConfig();
            this.tlsEngine = new QuicTlsEngine(certConfig, this.soConfig, null, true);
        }
    }

    /**
     * Processes a received Initial packet on the server side.
     * Extracts the CRYPTO frame, feeds it to the TLS engine, and sends back
     * ServerHello (Initial) + server handshake messages (Handshake).
     * @param parsed the decrypted Initial packet
     * @param clientAddr the client's address for sending responses
     * @return {@code true} if the Initial was processed successfully
     */
    public boolean processServerInitial(QuicPacket.ParsedPacket parsed, SocketAddress clientAddr) throws Exception {
        if (this.state != QuicAsyncHandshakeState.INITIAL) {
            return false;
        }

        // Extract peer's SCID as our remote CID
        this.remoteCid = parsed.scid != null ? parsed.scid : new byte[0];

        // Update largest received PN
        updateLargestInitialPn(parsed.packetNumber);

        // Extract CRYPTO frame from payload
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            logger.error("No CRYPTO frame found in Initial packet");
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] cryptoData = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, cryptoData, 0, dataLength);

        if (this.soConfig.isSslEnabled()) {
            return processServerInitialWithTls(cryptoData, clientAddr);
        } else {
            return processServerInitialNoTls(cryptoData, clientAddr, parsed);
        }
    }

    // ── Client-side handshake processing ──────────────────────────────

    private boolean processServerInitialWithTls(byte[] clientHello, SocketAddress clientAddr) throws Exception {
        // Process ClientHello through TLS engine
        if (!this.tlsEngine.processClientHello(clientHello)) {
            logger.error("Failed to process ClientHello");
            return false;
        }

        // Install handshake keys from TLS engine
        this.clientHandshakeKeys = this.tlsEngine.getClientHandshakeKeys();
        this.serverHandshakeKeys = this.tlsEngine.getServerHandshakeKeys();

        // ── Send ServerHello in an Initial packet ─────────────────────
        byte[] serverHello = this.tlsEngine.getServerHelloBytes();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, serverHello);
        byte[] ackFrame = QuicPacket.buildAckFrame(this.largestInitialPn.get(), 0);
        byte[] payload = concat(ackFrame, cryptoFrame);

        byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.INITIAL);
        long pn = nextInitialPacketNumber();
        byte[] packet = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                this.remoteCid, this.localCid, new byte[0], pn, payload,//
                sendKeys[0], sendKeys[1], sendKeys[2], 1200);
        sendPacket(packet, clientAddr);

        // ── Send Handshake messages in a Handshake packet ─────────────
        byte[] hsBytes = this.tlsEngine.getHandshakeBytes();
        byte[] hsCryptoFrame = QuicPacket.buildCryptoFrame(0, hsBytes);
        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        long hsPn = nextHandshakePacketNumber();
        byte[] hsPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                this.remoteCid, this.localCid, null, hsPn, hsCryptoFrame,//
                hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
        sendPacket(hsPacket, clientAddr);

        this.state = QuicAsyncHandshakeState.HANDSHAKE;
        return true;
    }

    private boolean processServerInitialNoTls(byte[] cryptoData, SocketAddress clientAddr, QuicPacket.ParsedPacket parsed) throws Exception {
        // Non-TLS mode: echo back a simple ServerHello-like Initial + transition to ESTABLISHED
        byte[] ackFrame = QuicPacket.buildAckFrame(this.largestInitialPn.get(), 0);
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, new byte[0]);
        byte[] payload = concat(ackFrame, cryptoFrame);

        long pn = nextInitialPacketNumber();
        byte[] packet = QuicPacket.buildRawLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                this.remoteCid, this.localCid, new byte[0], pn, payload);
        sendPacket(packet, clientAddr);

        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        return true;
    }

    /**
     * Processes a received Handshake packet on the server side.
     * Expects the client's Finished message to complete the handshake.
     * @param parsed the decrypted Handshake packet
     * @param clientAddr the client's address
     * @return {@code true} if the handshake is now complete (ESTABLISHED)
     */
    public boolean processServerHandshake(QuicPacket.ParsedPacket parsed, SocketAddress clientAddr) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE) {
            return false;
        }

        updateLargestHandshakePn(parsed.packetNumber);

        // Extract CRYPTO frame (client Finished)
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            logger.error("No CRYPTO frame in Handshake packet");
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] clientFinished = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, clientFinished, 0, dataLength);

        if (this.soConfig.isSslEnabled()) {
            if (!this.tlsEngine.verifyClientFinished(clientFinished)) {
                logger.error("Client Finished verification failed");
                return false;
            }
            // Install 1-RTT application keys
            this.clientAppKeys = this.tlsEngine.getClientAppKeys();
            this.serverAppKeys = this.tlsEngine.getServerAppKeys();
        }

        // ── Send ACK for Handshake + HANDSHAKE_DONE in 1-RTT ─────────
        // ACK the client's Handshake packet
        byte[] hsAck = QuicPacket.buildAckFrame(this.largestHandshakePn.get(), 0);
        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        long hsAckPn = nextHandshakePacketNumber();
        byte[] hsAckPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                this.remoteCid, this.localCid, null, hsAckPn, hsAck,//
                hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
        sendPacket(hsAckPacket, clientAddr);

        // Send HANDSHAKE_DONE in a 1-RTT Short Header packet
        byte[] handshakeDone = QuicPacket.buildHandshakeDoneFrame();
        if (this.soConfig.isSslEnabled()) {
            byte[][] appSendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
            long appPn = nextAppPacketNumber();
            byte[] appPacket = QuicPacket.buildShortHeaderPacket(this.remoteCid, appPn, handshakeDone,//
                    appSendKeys[0], appSendKeys[1], appSendKeys[2]);
            sendPacket(appPacket, clientAddr);
        } else {
            // Non-TLS: send raw short header (no encryption)
            sendRaw1RttPacket(handshakeDone, clientAddr);
        }

        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        logger.info("QUIC handshake complete (server mode), remote=" + clientAddr);
        return true;
    }

    /**
     * Initiates a client-side QUIC handshake by sending an Initial packet
     * containing a TLS ClientHello CRYPTO frame.
     * @param serverAddr the server address to send to
     */
    public void initiateClientHandshake(SocketAddress serverAddr) throws Exception {
        // Client generates DCID for the server
        int cidLen = this.soConfig.getConnectionIdLength();
        this.remoteCid = new byte[cidLen];
        new SecureRandom().nextBytes(this.remoteCid);

        // Derive Initial keys from the client-chosen DCID
        deriveInitialKeys(this.remoteCid);

        if (this.soConfig.isSslEnabled()) {
            // Generate ClientHello via TLS engine
            byte[] clientHello = this.tlsEngine.generateClientHello();
            byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, clientHello);
            long pn = nextInitialPacketNumber();

            // Encrypt and send Initial packet (padded to 1200 bytes)
            byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.INITIAL);
            byte[] packet = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                    this.remoteCid, this.localCid, new byte[0], pn, cryptoFrame,//
                    sendKeys[0], sendKeys[1], sendKeys[2], 1200);
            sendPacket(packet, serverAddr);
        } else {
            // Non-TLS mode: send a minimal Initial packet with empty CRYPTO
            byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, new byte[0]);
            long pn = nextInitialPacketNumber();
            byte[] packet = QuicPacket.buildRawLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                    this.remoteCid, this.localCid, new byte[0], pn, cryptoFrame);
            sendPacket(packet, serverAddr);
        }

        this.state = QuicAsyncHandshakeState.HANDSHAKE;
    }

    // ── 1-RTT packet building ──────────────────────────────────────────

    /**
     * Processes a server's Initial response on the client side.
     * For non-TLS mode this transitions directly to ESTABLISHED.
     * @param parsed the decrypted/parsed Initial packet from the server
     * @return {@code true} if processing was successful
     */
    public boolean processClientInitialResponse(QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }

        // Update our remoteCid to server's SCID (now used as DCID in all our outgoing packets)
        this.remoteCid = parsed.scid != null ? parsed.scid : new byte[0];
        updateLargestInitialPn(parsed.packetNumber);

        if (!this.soConfig.isSslEnabled()) {
            // Non-TLS: server goes directly to ESTABLISHED after Initial
            this.state = QuicAsyncHandshakeState.ESTABLISHED;
            logger.info("QUIC handshake complete (client mode, non-TLS)");
            return true;
        }

        // TLS mode: extract CRYPTO frame (ServerHello) and process
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            logger.error("No CRYPTO frame in server Initial packet");
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] serverHello = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, serverHello, 0, dataLength);

        if (!this.tlsEngine.processServerHello(serverHello)) {
            logger.error("Failed to process ServerHello");
            return false;
        }

        // Install handshake keys from TLS engine
        this.clientHandshakeKeys = this.tlsEngine.getClientHandshakeKeys();
        this.serverHandshakeKeys = this.tlsEngine.getServerHandshakeKeys();
        logger.info("Client handshake keys installed, waiting for server Handshake packet");
        return true;
    }

    // ── Packet sending ─────────────────────────────────────────────────

    /**
     * Processes a server's Handshake packet on the client side.
     * Extracts server handshake messages, then sends client Finished.
     * @param parsed the decrypted Handshake packet
     * @return {@code true} if processing was successful
     */
    public boolean processClientHandshakeResponse(QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }

        updateLargestHandshakePn(parsed.packetNumber);

        if (!this.soConfig.isSslEnabled()) {
            // Non-TLS: shouldn't receive Handshake packet — ignore
            return false;
        }

        // TLS mode: extract CRYPTO frame (server EncryptedExtensions + Certificate + CertificateVerify + Finished)
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            logger.error("No CRYPTO frame in server Handshake packet");
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] hsData = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, hsData, 0, dataLength);

        if (!this.tlsEngine.processServerHandshakeMessages(hsData)) {
            logger.error("Failed to process server handshake messages");
            return false;
        }

        // Install 1-RTT application keys
        this.clientAppKeys = this.tlsEngine.getClientAppKeys();
        this.serverAppKeys = this.tlsEngine.getServerAppKeys();

        // ── Send ACK for server Handshake + client Finished ───────────
        byte[] hsAck = QuicPacket.buildAckFrame(this.largestHandshakePn.get(), 0);
        byte[] clientFinished = this.tlsEngine.getClientFinishedBytes();
        byte[] clientFinCrypto = QuicPacket.buildCryptoFrame(0, clientFinished);
        byte[] finPayload = concat(hsAck, clientFinCrypto);

        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        long hsPn = nextHandshakePacketNumber();
        byte[] finPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                this.remoteCid, this.localCid, null, hsPn, finPayload,//
                hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
        sendPacket(finPacket, this.remoteAddress);

        logger.info("Client Finished sent, waiting for HANDSHAKE_DONE");
        return true;
    }

    /**
     * Processes a HANDSHAKE_DONE frame received in a 1-RTT packet on the client side.
     * Transitions the handshake to ESTABLISHED.
     * @return {@code true} if the handshake transitioned to ESTABLISHED
     */
    public boolean processHandshakeDone() {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }
        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        logger.info("QUIC handshake complete (client mode, received HANDSHAKE_DONE)");
        return true;
    }

    // ── Packet receiving helpers ───────────────────────────────────────

    /**
     * Builds a 1-RTT (Short Header) QUIC packet containing the given payload frames.
     * If TLS is enabled, the packet is encrypted with application-level keys.
     * If TLS is disabled, a raw packet is built without encryption.
     * @param payload the QUIC frames to include in the packet payload
     * @return the complete QUIC packet bytes ready to send over UDP
     */
    public byte[] build1RttPacket(byte[] payload) throws Exception {
        if (this.soConfig.isSslEnabled()) {
            byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
            if (sendKeys == null) {
                throw new IllegalStateException("Application keys not yet derived; handshake incomplete");
            }
            long pn = nextAppPacketNumber();
            return QuicPacket.buildShortHeaderPacket(this.remoteCid, pn, payload,//
                    sendKeys[0], sendKeys[1], sendKeys[2]);
        } else {
            return buildRaw1RttPacket(payload);
        }
    }

    /**
     * Sends a raw QUIC packet over UDP to the specified address.
     * @param packet the complete packet bytes
     * @param target the destination address
     * @return the number of bytes sent
     */
    int sendPacket(byte[] packet, SocketAddress target) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        if (target != null) {
            return this.udpChannel.send(buf, target);
        } else {
            return this.udpChannel.write(buf);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Sends a raw QUIC packet over UDP to the configured remote address.
     * @param packet the complete packet bytes
     * @return the number of bytes sent
     */
    int sendPacket(byte[] packet) throws Exception {
        return sendPacket(packet, this.remoteAddress);
    }

    /**
     * Decrypts a received Long Header packet using the appropriate keys for the given level.
     * @param data the raw packet data
     * @param offset offset into the data array
     * @param parsed the pre-parsed packet header
     * @param level the encryption level ({@link #LEVEL_INITIAL} or {@link #LEVEL_HANDSHAKE})
     * @return {@code true} if decryption succeeded
     */
    public boolean decryptLongHeaderPacket(byte[] data, int offset, QuicPacket.ParsedPacket parsed, int level) {
        if (!this.soConfig.isSslEnabled()) {
            return true; // no encryption
        }
        QuicAsyncHandshakeState hsLevel = levelToState(level);
        byte[][] recvKeys = getRecvKeys(hsLevel);
        if (recvKeys == null) {
            return false;
        }
        long largestPn = (hsLevel == QuicAsyncHandshakeState.INITIAL) ? this.largestInitialPn.get() : this.largestHandshakePn.get();
        return QuicPacket.decryptLongHeaderPacket(data, offset, parsed, recvKeys[0], recvKeys[1], recvKeys[2], Math.max(largestPn, 0));
    }

    /**
     * Decrypts a received Short Header (1-RTT) packet.
     * @param data the raw packet data
     * @param offset offset into the data array
     * @param length length of the packet data
     * @return the decrypted parsed packet, or {@code null} if decryption fails
     */
    public QuicPacket.ParsedPacket decrypt1RttPacket(byte[] data, int offset, int length) {
        if (!this.soConfig.isSslEnabled()) {
            return QuicAsyncServerChannel.parseRawShortHeader(data, this.localCid.length);
        }
        byte[][] recvKeys = getRecvKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (recvKeys == null) {
            return null;
        }
        return QuicPacket.decryptShortHeaderPacket(data, offset, length, this.localCid.length,//
                recvKeys[0], recvKeys[1], recvKeys[2], Math.max(this.largestAppPn.get(), 0));
    }

    /** Builds a raw (unencrypted) 1-RTT short header packet for non-TLS mode. */
    private byte[] buildRaw1RttPacket(byte[] payload) {
        int pnLength = 1;
        long pn = nextAppPacketNumber();
        byte[] pnBytes = QuicPacket.encodePacketNumber(pn, pnLength);

        int totalSize = 1 + this.remoteCid.length + pnLength + payload.length;
        byte[] packet = new byte[totalSize];
        int pos = 0;
        packet[pos++] = (byte) (0x40 | (pnLength - 1));
        System.arraycopy(this.remoteCid, 0, packet, pos, this.remoteCid.length);
        pos += this.remoteCid.length;
        System.arraycopy(pnBytes, 0, packet, pos, pnLength);
        pos += pnLength;
        System.arraycopy(payload, 0, packet, pos, payload.length);
        return packet;
    }

    /** Sends a raw 1-RTT packet with the given payload to the specified address. */
    private void sendRaw1RttPacket(byte[] payload, SocketAddress target) throws Exception {
        byte[] packet = buildRaw1RttPacket(payload);
        sendPacket(packet, target);
    }

    /**
     * Builds a {@link QuicInitConfigData} from the peer's transport parameters
     * extracted during the TLS handshake.
     * @param localAddr the local socket address
     * @param remoteAddr the remote socket address
     * @return the negotiated configuration data, or a default instance if no TLS was used
     */
    public QuicInitConfigData buildInitConfigData(SocketAddress localAddr, SocketAddress remoteAddr) {
        QuicInitConfigData data = new QuicInitConfigData();
        data.setLocalAddr(localAddr);
        data.setRemoteAddr(remoteAddr);

        if (this.tlsEngine != null) {
            byte[] peerParams = this.tlsEngine.getPeerTransportParams();
            if (peerParams != null && peerParams.length > 0) {
                // Parse QUIC transport parameters (RFC 9000 §18): each param is varint(id) + varint(len) + value
                int pos = 0;
                while (pos < peerParams.length) {
                    long[] idResult = QuicVarInt.decode(peerParams, pos);
                    int paramId = (int) idResult[0];
                    pos += (int) idResult[1];

                    long[] lenResult = QuicVarInt.decode(peerParams, pos);
                    int paramLen = (int) lenResult[0];
                    pos += (int) lenResult[1];

                    // Decode the value as a varint (most transport params are varint-encoded integers)
                    long paramValue = 0;
                    if (paramLen > 0 && pos < peerParams.length) {
                        long[] valResult = QuicVarInt.decode(peerParams, pos);
                        paramValue = valResult[0];
                    }
                    pos += paramLen;

                    switch (paramId) {
                        case PARAM_INITIAL_MAX_DATA:
                            data.setPeerMaxData(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAMS_BIDI:
                            data.setPeerMaxStreamsBidi(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAMS_UNI:
                            data.setPeerMaxStreamsUni(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL:
                            // Peer's bidi local = limits our sends on peer-initiated bidi streams
                            data.setPeerStreamMaxDataBidiLocal(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE:
                            // Peer's bidi remote = limits our sends on our-initiated bidi streams
                            data.setPeerStreamMaxDataBidiRemote(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_UNI:
                            data.setPeerStreamMaxDataUni(paramValue);
                            break;
                        case PARAM_MAX_DATAGRAM_FRAME_SIZE:
                            data.setDatagramMaxDataSize(paramValue);
                            break;
                        default:
                            // Unknown or unsupported transport parameter — skip
                            break;
                    }
                }
                return data;
            }
        }

        // Fallback: use local soConfig defaults when no peer params available
        data.setPeerMaxData(this.soConfig.getTpInitialFrameMaxData());
        data.setPeerMaxStreamsBidi(this.soConfig.getTpInitialMaxStreamsBidi());
        data.setPeerMaxStreamsUni(this.soConfig.getTpInitialMaxStreamsUni());
        data.setPeerStreamMaxDataBidiLocal(this.soConfig.getTpInitialMaxStreamDataBidiLocal());
        data.setPeerStreamMaxDataBidiRemote(this.soConfig.getTpInitialMaxStreamDataBidiRemote());
        data.setPeerStreamMaxDataUni(this.soConfig.getTpInitialMaxStreamDataUni());
        data.setDatagramMaxDataSize(this.soConfig.getTpInitialDatagramFrameMaxData());
        return data;
    }

    // ── 0-RTT data buffering and draining ────────────────────────────

    /**
     * Buffers a raw 0-RTT packet received before handshake completes.
     * These will be delivered after the handshake is established.
     */
    public void buffer0RttData(byte[] rawData) {
        synchronized (this.buffered0RttData) {
            if (this.buffered0RttData.size() < 64) { // limit buffer to prevent memory issues
                this.buffered0RttData.add(rawData);
            } else {
                logger.info("0-RTT buffer full, discarding packet");
            }
        }
    }

    /**
     * Drains any buffered 0-RTT data. Returns the list of buffered packets
     * and clears the buffer.
     */
    public List<byte[]> drain0RttData() {
        synchronized (this.buffered0RttData) {
            if (this.buffered0RttData.isEmpty()) {
                return new ArrayList<byte[]>();
            }
            List<byte[]> result = new ArrayList<byte[]>(this.buffered0RttData);
            this.buffered0RttData.clear();
            return result;
        }
    }

    /** Returns true if there are buffered 0-RTT packets. */
    public boolean has0RttData() {
        return !this.buffered0RttData.isEmpty();
    }

    // ── Key Update (RFC 9001 §6, RFC 8446 §7.2) ───────────────────────

    /**
     * Rotates the READ keys (i.e., the keys used to decrypt incoming packets).
     * Called when a KeyUpdate message is received from the peer.
     * <p>
     * Uses HKDF-Expand-Label to derive next-generation keys from the current
     * application traffic secrets, per RFC 8446 §7.2:
     * <pre>
     *   application_traffic_secret_N+1 = HKDF-Expand-Label(
     *       application_traffic_secret_N, "traffic upd", "", Hash.length)
     * </pre>
     */
    public void rotateReadKeys() throws Exception {
        // Determine which keys are our read keys
        byte[][] currentRecvKeys = getRecvKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (currentRecvKeys == null || currentRecvKeys.length < 3) {
            throw new IllegalStateException("No application read keys available for rotation");
        }
        // Derive new traffic secret using HKDF-Expand-Label with "quic ku" label
        // RFC 9001 §6.1: uses "quic ku" label for QUIC key update
        byte[] currentSecret = currentRecvKeys[0]; // key as secret (simplified)
        byte[] newKey = QuicCrypto.hkdfExpandLabel(currentSecret, "quic ku", new byte[0], 16, "quic ");
        byte[] newIv = QuicCrypto.hkdfExpandLabel(currentSecret, "quic iv", new byte[0], 12, "quic ");
        // HP key remains the same after key update (RFC 9001 §6.6)
        byte[] hp = currentRecvKeys[2];
        byte[][] newRecvKeys = new byte[][] { newKey, newIv, hp };
        // Install new read keys
        if (this.clientMode) {
            this.serverAppKeys = newRecvKeys;
        } else {
            this.clientAppKeys = newRecvKeys;
        }
        logger.info("Read keys rotated (generation " + (this.keyUpdateGeneration + 1) + ")");
    }

    /**
     * Rotates the WRITE keys (i.e., the keys used to encrypt outgoing packets).
     * Called when we need to update our sending keys (either initiated by us or
     * in response to a peer's KeyUpdate with request_update=1).
     */
    public void rotateWriteKeys() throws Exception {
        byte[][] currentSendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (currentSendKeys == null || currentSendKeys.length < 3) {
            throw new IllegalStateException("No application write keys available for rotation");
        }
        byte[] currentSecret = currentSendKeys[0];
        byte[] newKey = QuicCrypto.hkdfExpandLabel(currentSecret, "quic ku", new byte[0], 16, "quic ");
        byte[] newIv = QuicCrypto.hkdfExpandLabel(currentSecret, "quic iv", new byte[0], 12, "quic ");
        byte[] hp = currentSendKeys[2];
        byte[][] newSendKeys = new byte[][] { newKey, newIv, hp };
        if (this.clientMode) {
            this.clientAppKeys = newSendKeys;
        } else {
            this.serverAppKeys = newSendKeys;
        }
        this.keyUpdateGeneration++;
        logger.info("Write keys rotated (generation " + this.keyUpdateGeneration + ")");
    }

    /** Returns the current key update generation number. */
    public int getKeyUpdateGeneration() {
        return this.keyUpdateGeneration;
    }

    /** Handshake state machine */
    private enum QuicAsyncHandshakeState {
        INITIAL,
        HANDSHAKE,
        ESTABLISHED,
        CLOSED
    }
}
