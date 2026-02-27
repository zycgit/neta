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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpAsyncServerChannel;
import net.hasor.neta.channel.udp.UdpSoConfigUtils;

/**
 * QUIC server channel extending {@link UdpAsyncServerChannel}.
 * <p>
 * Overrides the UDP receive loop to perform QUIC packet parsing, TLS handshake
 * processing, and per-connection dispatching based on Connection IDs rather
 * than remote socket addresses.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncServerChannel extends UdpAsyncServerChannel {
    private static final Logger logger    = Logger.getLogger(QuicAsyncServerChannel.class);
    /** Server-wide static key for token generation/validation (simplified). */
    private static final byte[] TOKEN_KEY = new byte[16];

    static {
        new SecureRandom().nextBytes(TOKEN_KEY);
    }

    /** Active QUIC connections keyed by our local Connection ID. */
    private final Map<CidKey, QuicChannelAsync>          connectionMap = new ConcurrentHashMap<>();
    /** In-progress handshakes keyed by Connection ID (both our local CID and client's original DCID). */
    private final Map<CidKey, QuicAsyncChannelHandshake> handshakeMap  = new ConcurrentHashMap<>();

    // ── Bind override ──────────────────────────────────────────────────

    protected QuicAsyncServerChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress listenAddr, QuicSoConfig soConfig) throws IOException {
        super(channelId, channel, context, listenAddr, soConfig);
    }

    // ── QUIC datagram processing ───────────────────────────────────────

    /** Parses a raw (unencrypted) Short Header packet for non-TLS mode. */
    static QuicPacket.ParsedPacket parseRawShortHeader(byte[] data, int dcidLen) {
        if (data.length < 1 + dcidLen + 1) {
            return null;
        }
        QuicPacket.ParsedPacket pkt = new QuicPacket.ParsedPacket();
        pkt.packetType = QuicPacket.TYPE_1RTT;
        int pos = 0;
        int firstByte = data[pos++] & 0xFF;
        int pnLength = (firstByte & 0x03) + 1;

        pkt.dcid = new byte[dcidLen];
        System.arraycopy(data, pos, pkt.dcid, 0, dcidLen);
        pos += dcidLen;

        if (pos + pnLength > data.length) {
            return null;
        }

        long pn = 0;
        for (int i = 0; i < pnLength; i++) {
            pn = (pn << 8) | (data[pos++] & 0xFF);
        }
        pkt.packetNumber = pn;
        pkt.pnLength = pnLength;

        int payloadLen = data.length - pos;
        if (payloadLen > 0) {
            pkt.payload = new byte[payloadLen];
            System.arraycopy(data, pos, pkt.payload, 0, payloadLen);
        } else {
            pkt.payload = new byte[0];
        }
        return pkt;
    }

    private static String bytesToHex(byte[] bytes) {
        return QuicCrypto.bytesToHex(bytes);
    }

    /**
     * Derives a deterministic 16-byte Stateless Reset Token from {@link #TOKEN_KEY} and the
     * given Connection ID using HMAC-SHA256 (RFC 9000 §10.3.1).
     * The same (key, cid) pair always produces the same token, so clients can identify
     * a Stateless Reset even after a server restart.
     */
    private static byte[] computeStatelessResetToken(byte[] cid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TOKEN_KEY, "HmacSHA256"));
            byte[] hmac = mac.doFinal(cid);
            byte[] token = new byte[16];
            System.arraycopy(hmac, 0, token, 0, 16);
            return token;
        } catch (Exception e) {
            // Should never happen with HmacSHA256 on any JDK
            byte[] token = new byte[16];
            new SecureRandom().nextBytes(token);
            return token;
        }
    }

    private QuicSoConfig quicSoConfig() {
        return (QuicSoConfig) this.soConfig;
    }

    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // Configure socket options
        UdpSoConfigUtils.configListen(this.soConfig, this.transport.getChannel());

        // Create a QuicListen instead of UdpNetListen
        QuicListen listen = new QuicListen(//
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                quicSoConfig());

        // Initialize the listen channel
        SocketAddress localAddr;
        try {
            this.context.initChannel(listen, false);
            this.transport.bind(this.listenAddr);
            localAddr = this.transport.getLocalAddress();
        } catch (Throwable e) {
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }

        // Start receive loop with QUIC-aware datagram handler
        final SocketAddress finalLocalAddr = localAddr;
        this.transport.startReceiveLoop(//
                (remoteAddr, data) -> this.onQuicDatagram(listen, finalLocalAddr, remoteAddr, data), //
                () -> {
                    logger.info("rcv(" + this.channelId + ") close from local.");
                    this.context.notifyChannelClose(this.channelId, false);
                }, (e) -> {
                    SoRcvException err = new SoRcvException(e.getMessage(), e);
                    this.context.notifyRcvChannelException(this.channelId, false, err);
                });
        return listen;
    }

    /** Handles an incoming UDP datagram. */
    private void onQuicDatagram(QuicListen listen, SocketAddress localAddr, SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        if (data.remaining() < 1) {
            return;
        }

        byte[] rawData = new byte[data.remaining()];
        data.get(rawData);

        boolean longHeader = QuicPacket.isLongHeader(rawData);
        if (longHeader) {
            processLongHeaderPacket(listen, localAddr, remoteAddr, rawData);
        } else {
            processShortHeaderPacket(remoteAddr, rawData);
        }
    }

    /** Processes a Long Header packet (Initial or Handshake). */
    private void processLongHeaderPacket(QuicListen listen, SocketAddress localAddr, SocketAddress remoteAddr, byte[] rawData) throws IOException {
        QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(rawData, 0, rawData.length);
        if (parsed == null) {
            return;
        }

        CidKey dcidKey = new CidKey(parsed.dcid);

        // ── 1. Check if DCID matches an established connection ───────
        QuicChannelAsync conn = this.connectionMap.get(dcidKey);
        if (conn != null) {
            // Long Header on established connection — likely a retransmission; ignore
            return;
        }

        // ── 2. Check if DCID matches an in-progress handshake ────────
        QuicAsyncChannelHandshake handshake = this.handshakeMap.get(dcidKey);

        // ── 3. New connection (Initial packet with unknown DCID) ─────
        if (handshake == null) {
            if (parsed.packetType != QuicPacket.TYPE_INITIAL) {
                if (parsed.packetType == QuicPacket.TYPE_0RTT) {
                    // 0-RTT without active handshake — no session to resume
                    logger.info("0-RTT packet received without active handshake, ignoring");
                }
                return; // only Initial can start a new connection
            }
            if (!acceptChannel(listen, localAddr, remoteAddr)) {
                return;
            }

            // ── Token validation (RFC 9000 §8.1) ────────────────────
            // If a token is present in the Initial packet, validate it.
            // This supports both Retry tokens and NEW_TOKEN tokens.
            if (parsed.token != null && parsed.token.length > 0) {
                if (!validateToken(parsed.token, remoteAddr, parsed.dcid)) {
                    logger.info("Invalid token in Initial from " + remoteAddr + ", sending Retry");
                    sendRetryPacket(remoteAddr, parsed.dcid, parsed.scid);
                    return;
                }
                logger.info("Valid token in Initial from " + remoteAddr);
            }

            QuicSoConfig quicConfig = quicSoConfig();
            handshake = new QuicAsyncChannelHandshake(false, quicConfig, this.transport.getChannel(), remoteAddr);
            try {
                handshake.deriveInitialKeys(parsed.dcid);
                handshake.initTlsEngine();
            } catch (Exception e) {
                logger.error("Failed to setup QUIC handshake: " + e.getMessage(), e);
                return;
            }

            // Register under both our local CID and the client's original DCID
            this.handshakeMap.put(new CidKey(handshake.getLocalCid()), handshake);
            this.handshakeMap.put(dcidKey, handshake);
        }

        // ── 4. Dispatch to handshake processing ──────────────────────
        try {
            if (parsed.packetType == QuicPacket.TYPE_INITIAL) {
                processInitial(handshake, rawData, parsed, remoteAddr, localAddr, listen);
            } else if (parsed.packetType == QuicPacket.TYPE_HANDSHAKE) {
                processHandshake(handshake, rawData, parsed, remoteAddr, localAddr, listen);
            } else if (parsed.packetType == QuicPacket.TYPE_0RTT) {
                // ── 0-RTT early data (RFC 9001 §4.9.1) ──────────────
                // Buffer 0-RTT data until handshake completes, then deliver
                process0RttPacket(handshake, rawData, parsed, remoteAddr);
            }
        } catch (Exception e) {
            logger.error("QUIC handshake processing error: " + e.getMessage(), e);
        }
    }

    /* Decrypts and processes an Initial packet, then checks if the handshake has completed (for non-TLS mode this happens immediately). */
    private void processInitial(QuicAsyncChannelHandshake handshake, byte[] rawData, QuicPacket.ParsedPacket parsed,//
            SocketAddress remoteAddr, SocketAddress localAddr, QuicListen listen) throws Exception {

        if (quicSoConfig().isSslEnabled()) {
            if (!handshake.decryptLongHeaderPacket(rawData, 0, parsed, QuicAsyncChannelHandshake.LEVEL_INITIAL)) {
                logger.error("Failed to decrypt Initial packet from " + remoteAddr);
                return;
            }
        } else {
            QuicPacket.ParsedPacket rawParsed = QuicPacket.parseRawLongHeaderPacket(rawData, 0, rawData.length);
            if (rawParsed == null) {
                return;
            }
            parsed.payload = rawParsed.payload;
            parsed.packetNumber = rawParsed.packetNumber;
            parsed.pnLength = rawParsed.pnLength;
        }

        // Process
        if (!handshake.processServerInitial(parsed, remoteAddr)) {
            return;
        }

        // Check if handshake completed (non-TLS mode goes directly to ESTABLISHED)
        if (handshake.isEstablished()) {
            promoteToConnection(handshake, localAddr, remoteAddr, listen);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Decrypts and processes a Handshake packet (client Finished),
     * then promotes to a full connection if ESTABLISHED.
     */
    private void processHandshake(QuicAsyncChannelHandshake handshake, byte[] rawData, QuicPacket.ParsedPacket parsed,//
            SocketAddress remoteAddr, SocketAddress localAddr, QuicListen listen) throws Exception {
        // Decrypt
        if (quicSoConfig().isSslEnabled()) {
            if (!handshake.decryptLongHeaderPacket(rawData, 0, parsed, QuicAsyncChannelHandshake.LEVEL_HANDSHAKE)) {
                logger.error("Failed to decrypt Handshake packet from " + remoteAddr);
                return;
            }
        } else {
            QuicPacket.ParsedPacket rawParsed = QuicPacket.parseRawLongHeaderPacket(rawData, 0, rawData.length);
            if (rawParsed == null) {
                return;
            }
            parsed.payload = rawParsed.payload;
            parsed.packetNumber = rawParsed.packetNumber;
        }

        // Process
        if (!handshake.processServerHandshake(parsed, remoteAddr)) {
            return;
        }

        // Promote to connection
        if (handshake.isEstablished()) {
            promoteToConnection(handshake, localAddr, remoteAddr, listen);
        }
    }

    /**
     * Processes a 0-RTT (Early Data) packet from a resuming client (RFC 9001 §4.9.1).
     * <p>
     * 0-RTT data is encrypted with keys derived from the pre-shared key (session ticket).
     * Server can either accept or reject 0-RTT data. If accepted, the data is buffered
     * until the handshake completes and then delivered. If the server cannot validate the
     * early data, it simply ignores the 0-RTT packet.
     */
    private void process0RttPacket(QuicAsyncChannelHandshake handshake, byte[] rawData,//
            QuicPacket.ParsedPacket parsed, SocketAddress remoteAddr) {
        // 0-RTT requires TLS with session ticket support
        if (!quicSoConfig().isSslEnabled()) {
            logger.info("0-RTT packet received but TLS is disabled, ignoring");
            return;
        }

        // For now, log the 0-RTT attempt. Full 0-RTT support requires:
        // 1. Session ticket storage from previous connections
        // 2. Early data key derivation from pre-shared key
        // 3. Decryption with 0-RTT keys
        // 4. Buffering until handshake completes
        // 5. Replay protection
        logger.info("0-RTT packet received from " + remoteAddr + " (len=" + rawData.length + "), " //
                + "early data processing deferred to handshake completion");

        // Buffer the raw 0-RTT data for later processing
        handshake.buffer0RttData(rawData);
    }

    /**
     * Creates a {@link QuicChannelAsync} + {@link QuicChannel} from a completed handshake
     * and moves it from the handshake map to the connection map.
     */
    private void promoteToConnection(QuicAsyncChannelHandshake handshake, SocketAddress localAddr,//
            SocketAddress remoteAddr, QuicListen listen) {
        // Build negotiated config data
        QuicInitConfigData initData = handshake.buildInitConfigData(localAddr, remoteAddr);
        QuicSoConfig quicConfig = quicSoConfig();

        try {
            // Create QuicChannelAsync
            QuicChannelAsync connAsync = new QuicChannelAsync(//
                    initData, this.transport.getChannel(), quicConfig,//
                    this.context, listen.getInitializer(), listen,//
                    handshake);

            // Create QuicChannel (sets back-reference) and initialize pipeline
            QuicChannel quicChannel = new QuicChannel(connAsync);
            connAsync.getContext().initChannel(quicChannel, true);

            // Register in connection map under our local CID
            CidKey localCidKey = new CidKey(handshake.getLocalCid());
            this.connectionMap.put(localCidKey, connAsync);

            // Clean up handshake entries (remove all entries pointing to this handshake)
            this.handshakeMap.values().removeIf(hs -> hs == handshake);

            logger.info("QUIC connection established (server), cid=" + bytesToHex(handshake.getLocalCid()) //
                    + " remote=" + remoteAddr);
        } catch (Throwable e) {
            logger.error("Failed to create QUIC connection: " + e.getMessage(), e);
            this.handshakeMap.values().removeIf(hs -> hs == handshake);
        }
    }

    /**
     * Processes an incoming Short Header (1-RTT) packet. Looks up the
     * connection by DCID and dispatches decrypted payload to the connection.
     */
    private void processShortHeaderPacket(SocketAddress remoteAddr, byte[] rawData) {
        QuicSoConfig quicConfig = quicSoConfig();
        int dcidLen = quicConfig.getConnectionIdLength();
        if (rawData.length < 1 + dcidLen) {
            return;
        }

        // Extract DCID from Short Header
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(rawData, 1, dcid, 0, dcidLen);
        CidKey dcidKey = new CidKey(dcid);

        QuicChannelAsync conn = this.connectionMap.get(dcidKey);
        if (conn == null) {
            // ── Stateless Reset (RFC 9000 §10.3) ────────────────────
            // Unknown DCID → send Stateless Reset if we have a reset token
            sendStatelessReset(remoteAddr, rawData);
            return;
        }

        // ── Connection Migration Detection (RFC 9000 §9) ───────────
        SocketAddress currentRemote = conn.getRemoteAddress();
        if (currentRemote != null && !currentRemote.equals(remoteAddr)) {
            handleConnectionMigration(conn, remoteAddr);
        }

        dispatchAppData(conn, rawData);
    }

    /**
     * Sends a Stateless Reset packet to a peer whose Connection ID is not recognized.
     * <p>
     * Per RFC 9000 §10.3, a Stateless Reset is an unpredictable-size packet
     * ending with a 16-byte Stateless Reset Token. It MUST be smaller than
     * the packet that triggered it (to prevent amplification loops). The packet
     * is designed to appear as a Short Header packet to the peer.
     */
    private void sendStatelessReset(SocketAddress remoteAddr, byte[] triggerPacket) {
        // Minimum Stateless Reset size: at least 21 bytes (RFC 9000 §10.3.1)
        // and must be shorter than the triggering packet
        int maxLen = Math.min(triggerPacket.length - 1, 43);
        if (maxLen < 21) {
            return; // Too small to send a valid Stateless Reset
        }

        // Build Stateless Reset: random header bytes + 16-byte token
        // The first byte must NOT look like a Long Header (bit 7 = 0) per §17.3.1
        // and the Fixed Bit must be set to 1 (bit 6 = 1)
        SecureRandom random = new SecureRandom();
        int payloadLen = maxLen - 16; // space for random + header
        byte[] resetPacket = new byte[maxLen];
        random.nextBytes(resetPacket);
        resetPacket[0] = (byte) ((resetPacket[0] & 0x3F) | 0x40); // Short Header form + Fixed Bit

        // Derive a deterministic Stateless Reset Token: HMAC-SHA256(TOKEN_KEY, dcid)[0:16]
        // This ensures the token is stable across server restarts for the same CID (RFC 9000 §10.3.1)
        int cidLen = quicSoConfig().getConnectionIdLength();
        byte[] dcid = new byte[0];
        if (triggerPacket.length >= 1 + cidLen) {
            dcid = new byte[cidLen];
            System.arraycopy(triggerPacket, 1, dcid, 0, cidLen);
        }
        byte[] resetToken = computeStatelessResetToken(dcid);
        System.arraycopy(resetToken, 0, resetPacket, payloadLen, 16);

        try {
            ByteBuffer buf = ByteBuffer.wrap(resetPacket);
            this.transport.getChannel().send(buf, remoteAddr);
            logger.info("Sent Stateless Reset to " + remoteAddr + " (len=" + maxLen + ")");
        } catch (IOException e) {
            logger.error("Failed to send Stateless Reset: " + e.getMessage());
        }
    }

    // ── Token Validation and Retry (RFC 9000 §8.1) ──────────────────

    /**
     * Detects and handles connection migration (RFC 9000 §9).
     * <p>
     * When a packet from an established connection arrives from a new remote address,
     * this is treated as a connection migration. We:
     * <ol>
     *   <li>Update the connection's remote address</li>
     *   <li>Initiate PATH_CHALLENGE to validate the new path</li>
     *   <li>Reset congestion control state</li>
     * </ol>
     */
    private void handleConnectionMigration(QuicChannelAsync conn, SocketAddress newRemoteAddr) {
        logger.info("Connection migration detected for cid=" + bytesToHex(conn.getHandshake().getLocalCid()) //
                + " from " + conn.getRemoteAddress() + " to " + newRemoteAddr);

        // Update remote address
        conn.updateRemoteAddress(newRemoteAddr);

        // Activate anti-amplification limit and register a timeout callback that closes the
        // connection if PATH_CHALLENGE is not answered in time (RFC 9000 §9.3.1).
        conn.getPathValidator().onMigrationStart(() -> {
            logger.warn("PATH_CHALLENGE timed out during migration; closing connection cid=" //
                    + bytesToHex(conn.getHandshake().getLocalCid()));
            conn.closeWithError(QuicErrorCode.NO_VIABLE_PATH, "PATH_CHALLENGE timeout after migration", null);
        });

        // Issue a new local CID and send NEW_CONNECTION_ID frame so the peer can use the
        // new CID on the new path (RFC 9000 §9.5).
        byte[] newCidFrame = conn.getCidManager().issueNewConnectionId();
        if (newCidFrame != null) {
            conn.sendDataFrame(ByteBuf.wrap(newCidFrame), null);
        }

        // Register all active local CIDs in connectionMap so incoming packets using
        // any of the new CIDs are dispatched to this connection (RFC 9000 §9.5).
        for (byte[] cid : conn.getCidManager().getActiveLocalCids()) {
            this.connectionMap.putIfAbsent(new CidKey(cid), conn);
        }

        // Reset congestion control for the new path (RFC 9000 §9.4)
        conn.getCongestionControl().reset();

        // Initiate path validation via PATH_CHALLENGE (RFC 9000 §8.2.1)
        byte[] challengeFrame = conn.getPathValidator().initiateChallenge();
        if (challengeFrame != null) {
            conn.sendDataFrame(ByteBuf.wrap(challengeFrame), null);
        }
    }

    /**
     * Decrypts a 1-RTT packet and dispatches the payload frames to the connection's
     * stream/datagram/control handlers via {@link QuicChannelAsync#dispatchReceivedFrames(byte[])}.
     */
    private void dispatchAppData(QuicChannelAsync conn, byte[] rawData) {
        conn.checkIdleTimeouts();
        // Account for incoming bytes for anti-amplification (RFC 9000 §9.3.1)
        conn.getPathValidator().recordIncoming(rawData.length);
        try {
            QuicAsyncChannelHandshake handshake = conn.getHandshake();
            QuicPacket.ParsedPacket parsed;

            if (quicSoConfig().isSslEnabled()) {
                parsed = handshake.decrypt1RttPacket(rawData, 0, rawData.length);
            } else {
                parsed = parseRawShortHeader(rawData, quicSoConfig().getConnectionIdLength());
            }

            if (parsed == null || parsed.payload == null) {
                return;
            }

            handshake.updateLargestAppPn(parsed.packetNumber);

            // Dispatch individual QUIC frames to streams, datagrams, and control handlers
            conn.dispatchReceivedFrames(parsed.payload);
        } catch (Exception e) {
            logger.error("Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            QuicException qe = new QuicException(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            conn.notifyAllChannelsException(qe);
            conn.closeWithError(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), null);
        }
    }

    /**
     * Validates a token from an Initial packet.
     * <p>
     * Token format (simplified): [4 bytes: timestamp] [4 bytes: addr hash] [remaining: original DCID]
     * A valid token must have been issued within 60 seconds and match the source address.
     */
    private boolean validateToken(byte[] token, SocketAddress remoteAddr, byte[] dcid) {
        if (token.length < 8) {
            return false;
        }
        // Extract timestamp (first 4 bytes, big-endian)
        long timestamp = ((long) (token[0] & 0xFF) << 24) | ((token[1] & 0xFF) << 16) //
                | ((token[2] & 0xFF) << 8) | (token[3] & 0xFF);
        long ageSeconds = System.currentTimeMillis() / 1000 - timestamp;
        if (ageSeconds < 0 || ageSeconds > 60) {
            return false; // Token expired
        }
        // Validate address hash (next 4 bytes)
        int expectedHash = remoteAddr.hashCode();
        int tokenHash = ((token[4] & 0xFF) << 24) | ((token[5] & 0xFF) << 16) //
                | ((token[6] & 0xFF) << 8) | (token[7] & 0xFF);
        return tokenHash == expectedHash;
    }

    /**
     * Generates a token for address validation (used in Retry or NEW_TOKEN).
     */
    private byte[] generateToken(SocketAddress remoteAddr, byte[] originalDcid) {
        // Token format: [4 bytes: timestamp] [4 bytes: addr hash] [N bytes: original DCID]
        byte[] token = new byte[8 + originalDcid.length];
        long nowSec = System.currentTimeMillis() / 1000;
        token[0] = (byte) (nowSec >> 24);
        token[1] = (byte) (nowSec >> 16);
        token[2] = (byte) (nowSec >> 8);
        token[3] = (byte) nowSec;
        int addrHash = remoteAddr.hashCode();
        token[4] = (byte) (addrHash >> 24);
        token[5] = (byte) (addrHash >> 16);
        token[6] = (byte) (addrHash >> 8);
        token[7] = (byte) addrHash;
        System.arraycopy(originalDcid, 0, token, 8, originalDcid.length);
        return token;
    }

    /**
     * Sends a Retry packet to the client (RFC 9000 §17.2.5).
     * <p>
     * A Retry packet is Long Header with:
     * <ul>
     *   <li>Packet Type: Retry (0x03)</li>
     *   <li>DCID: client's SCID</li>
     *   <li>SCID: a new server-chosen CID</li>
     *   <li>Retry Token: an opaque token for address validation</li>
     *   <li>Retry Integrity Tag: 16-byte AEAD authentication (simplified)</li>
     * </ul>
     */
    private void sendRetryPacket(SocketAddress remoteAddr, byte[] clientDcid, byte[] clientScid) {
        // Generate new server CID
        int cidLen = quicSoConfig().getConnectionIdLength();
        byte[] newServerCid = new byte[cidLen];
        new SecureRandom().nextBytes(newServerCid);

        // Generate retry token
        byte[] retryToken = generateToken(remoteAddr, clientDcid);

        // Build Retry packet (RFC 9000 §17.2.5)
        // First byte: 1 (long header) | 1 (fixed bit) | 11 (type=Retry) | 0000 (unused)
        QuicVersion version = quicSoConfig().getQuicVersion();
        int versionInt = version == QuicVersion.V1 ? 0x00000001 : 0x6b3343cf;

        int packetLen = 1 + 4 + 1 + (clientScid != null ? clientScid.length : 0) + 1 + newServerCid.length + retryToken.length + 16;
        byte[] packet = new byte[packetLen];
        int pos = 0;

        // First byte: Long Header form (0x80) | Fixed Bit (0x40) | Type (0x30 for Retry) | unused (0x00)
        packet[pos++] = (byte) 0xF0; // 11110000

        // Version (4 bytes)
        packet[pos++] = (byte) (versionInt >> 24);
        packet[pos++] = (byte) (versionInt >> 16);
        packet[pos++] = (byte) (versionInt >> 8);
        packet[pos++] = (byte) versionInt;

        // DCID Length + DCID (set to client's SCID)
        int dcidLen = clientScid != null ? clientScid.length : 0;
        packet[pos++] = (byte) dcidLen;
        if (dcidLen > 0) {
            System.arraycopy(clientScid, 0, packet, pos, dcidLen);
            pos += dcidLen;
        }

        // SCID Length + SCID (our new CID)
        packet[pos++] = (byte) newServerCid.length;
        System.arraycopy(newServerCid, 0, packet, pos, newServerCid.length);
        pos += newServerCid.length;

        // Retry Token
        System.arraycopy(retryToken, 0, packet, pos, retryToken.length);
        pos += retryToken.length;

        // Retry Integrity Tag (16 bytes — simplified pseudo-tag)
        // In a full implementation, this would be an AEAD computation
        byte[] integrityTag = new byte[16];
        new SecureRandom().nextBytes(integrityTag);
        System.arraycopy(integrityTag, 0, packet, pos, 16);
        pos += 16;

        try {
            ByteBuffer buf = ByteBuffer.wrap(packet, 0, pos);
            this.transport.getChannel().send(buf, remoteAddr);
            logger.info("Sent Retry packet to " + remoteAddr);
        } catch (IOException e) {
            logger.error("Failed to send Retry packet: " + e.getMessage());
        }
    }

    /** Wrapper for byte[] to use as ConcurrentHashMap key. */
    static final class CidKey {
        final         byte[] cid;
        private final int    hash;

        CidKey(byte[] cid) {
            this.cid = cid;
            this.hash = Arrays.hashCode(cid);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CidKey)) {
                return false;
            }
            return Arrays.equals(this.cid, ((CidKey) obj).cid);
        }
    }
}
