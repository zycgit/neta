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
package net.hasor.neta.channel.transport.quic;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.transport.udp.UdpAsyncServerChannel;
import net.hasor.neta.channel.transport.udp.UdpSoConfigUtils;
/**
 * Server-side QUIC dispatcher built on top of a UDP listening channel; it is not a single QUIC
 * connection by itself.
 * <p>
 * It replaces the ordinary UDP receive loop with QUIC-aware processing, including parsing
 * coalesced packets, validating tokens, managing in-progress handshakes, looking up established
 * connections by CID, and promoting them to {@link QuicChannelAsync} plus the public
 * {@link QuicChannel} facade once the handshake completes.
 * <pre>
 *   Inbound UDP datagram
 *      +--> Parse one or more QUIC packets
 *      +--> Route by DCID
 *         +--> Established connection -> QuicChannelAsync
 *         +--> Handshake in progress  -> QuicAsyncChannelHandshake
 *         +--> New Initial            -> token/retry + new handshake
 * </pre>
 * <p>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncServerChannel extends UdpAsyncServerChannel {
    private static final Logger logger    = Logger.getLogger(QuicAsyncServerChannel.class);
    /** Server-wide static key used to generate and validate tokens; the current implementation is simplified. */
    private static final byte[] TOKEN_KEY = new byte[16];

    static {
        new SecureRandom().nextBytes(TOKEN_KEY);
    }

    /** Active QUIC connections keyed by the local Connection ID chosen by this endpoint. */
    private final Map<CidKey, QuicChannelAsync>          connectionMap = new ConcurrentHashMap<>();
    /** In-progress handshakes keyed by Connection ID, including the local CID and the client's original DCID. */
    private final Map<CidKey, QuicAsyncChannelHandshake> handshakeMap  = new ConcurrentHashMap<>();

    // ── Bind override ──────────────────────────────────────────────────

    protected QuicAsyncServerChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress listenAddr, QuicSoConfig soConfig) throws IOException {
        super(channelId, channel, context, listenAddr, soConfig);
    }

    // ── QUIC datagram processing ───────────────────────────────────────

    /**
     * Parses a raw unencrypted Short Header packet for non-TLS mode.
     * This method delegates to {@link QuicPacket#parseRawShortHeader} so the parsing logic stays centralized.
     */
    static QuicPacket.ParsedPacket parseRawShortHeader(byte[] data, int dcidLen) {
        return QuicPacket.parseRawShortHeader(data, dcidLen);
    }

    private static String bytesToHex(byte[] bytes) {
        return QuicCrypto.bytesToHex(bytes);
    }

    /**
     * Generates a deterministic 16-byte Stateless Reset Token (RFC 9000 §10.3.1) using
     * HMAC-SHA256 with TOKEN_KEY and the given CID.
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
            // HmacSHA256 should be available on any JDK; this path should not be reached in practice.
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
        // Configure socket options.
        UdpSoConfigUtils.configListen(this.soConfig, this.transport.getChannel());

        // Create a QuicListen instead of the default UdpNetListen.
        QuicListen listen = new QuicListen(//
                this.channelId,            //
                this.listenAddr,           //
                this.listenAddr.getPort(), //
                this,                      //
                initializer,               //
                this.context,              //
                quicSoConfig());

        // Initialize the listening channel.
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

        // Start the receive loop with a QUIC-aware datagram handler.
        final SocketAddress finalLocalAddr = localAddr;
        this.transport.startReceiveLoop(//
                (remoteAddr, data) -> this.onQuicDatagram(listen, finalLocalAddr, remoteAddr, data), //
                listen::isClose,// Exit when the listen handle closes, consistent with UdpAsyncServerChannel.
                () -> {
                    if (this.context.getConfig().isPrintLog()) {
                        logger.info("[QUIC] ch=" + this.channelId + " receive loop closed");
                    }
                    this.context.notifyChannelClose(this.channelId, false);
                }, (e) -> {
                    SoRcvException err = new SoRcvException(e.getMessage(), e);
                    this.context.notifyRcvChannelException(this.channelId, false, err);
                });
        return listen;
    }

    /**
     * Processes an inbound UDP datagram.
     */
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

    /**
     * Processes a Long Header packet such as Initial or Handshake; also supports coalesced packets
     * defined by RFC 9000 §12.2.
     */
    private void processLongHeaderPacket(QuicListen listen, SocketAddress localAddr, SocketAddress remoteAddr, byte[] rawData) throws IOException {
        int datagramOffset = 0;
        int datagramLength = rawData.length;

        // Iterate over coalesced QUIC packets inside the same UDP datagram.
        while (datagramOffset < datagramLength) {
            // Check whether the remaining data is still a Long Header; stop once a coalesced Short Header packet is reached.
            if (datagramOffset > 0 && !QuicPacket.isLongHeader(rawData[datagramOffset])) {
                break;
            }

            QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(rawData, datagramOffset, datagramLength - datagramOffset);
            if (parsed == null) {
                break; // The remaining data cannot be parsed; stop processing.
            }

            // Advance the offset past the current QUIC packet for the next iteration.
            // Note: parsed.headerLength is an absolute position within rawData and already includes datagramOffset.
            // Therefore nextOffset = parsed.headerLength + parsed.payloadLength; do not add datagramOffset again.
            int nextOffset = parsed.headerLength + parsed.payloadLength;
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] rcv type=" + parsed.packetType + " hdrLen=" + parsed.headerLength + " payLen=" + parsed.payloadLength + " from " + remoteAddr);
            }

            // Version negotiation (RFC 9000 §6).
            if (parsed.version != 0 && QuicVersion.fromVersion(parsed.version) == null) {
                sendVersionNegotiationPacket(remoteAddr, parsed.dcid, parsed.scid);
                datagramOffset = nextOffset;
                continue;
            }

            CidKey dcidKey = new CidKey(parsed.dcid);

            // 1. Check whether the DCID matches an established connection.
            QuicChannelAsync conn = this.connectionMap.get(dcidKey);
            if (conn != null) {
                if (parsed.packetType == QuicPacket.TYPE_0RTT) {
                    process0RttPacket(null, rawData, parsed, remoteAddr);
                }
                datagramOffset = nextOffset;
                continue;
            }

            // 2. Check whether the DCID matches an in-progress handshake.
            QuicAsyncChannelHandshake handshake = this.handshakeMap.get(dcidKey);

            // 3. New connection, represented by an Initial packet carrying an unknown DCID.
            if (handshake == null) {
                if (parsed.packetType != QuicPacket.TYPE_INITIAL) {
                    if (parsed.packetType == QuicPacket.TYPE_0RTT) {
                        if (this.context.getConfig().isPrintLog()) {
                            logger.info("[QUIC] 0-RTT rcv without active handshake, ignoring");
                        }
                    }
                    datagramOffset = nextOffset;
                    continue;
                }
                if (!acceptChannel(listen, localAddr, remoteAddr)) {
                    return;
                }

                // Token validation (RFC 9000 §8.1).
                // The Retry Integrity Tag (RFC 9001 §5.8) is computed with AEAD_AES_128_GCM using
                // the version-specific fixed key and nonce over the Retry Pseudo-Packet, so this path
                // is interoperable with standards-compliant QUIC clients.
                if (parsed.token != null && parsed.token.length > 0) {
                    // The client already carried a token, so validate it first.
                    if (!validateToken(parsed.token, remoteAddr, parsed.dcid)) {
                        if (this.context.getConfig().isPrintLog()) {
                            logger.info("[QUIC] Initial with invalid token len=" + parsed.token.length + " from " + remoteAddr + ", sending Retry");
                        }
                        sendRetryPacket(remoteAddr, parsed.dcid, parsed.scid);
                        datagramOffset = nextOffset;
                        continue;
                    }
                    // The token is valid, so continue with normal connection establishment.
                    if (this.context.getConfig().isPrintLog()) {
                        logger.info("[QUIC] Initial with valid token len=" + parsed.token.length + " from " + remoteAddr);
                    }
                }

                QuicSoConfig quicConfig = quicSoConfig();
                handshake = new QuicAsyncChannelHandshake(false, quicConfig, this.transport.getChannel(), remoteAddr, this.context.getConfig().isPrintLog());
                try {
                    handshake.deriveInitialKeys(parsed.dcid);
                    handshake.initTlsEngine();
                } catch (Exception e) {
                    logger.error("Failed to setup QUIC handshake: " + e.getMessage(), e);
                    return;
                }

                // Register under both our local CID and the client's original DCID.
                this.handshakeMap.put(new CidKey(handshake.getLocalCid()), handshake);
                this.handshakeMap.put(dcidKey, handshake);
            }

            // 4. Dispatch to handshake processing.
            try {
                if (parsed.packetType == QuicPacket.TYPE_INITIAL) {
                    processInitial(handshake, rawData, datagramOffset, parsed, remoteAddr, localAddr, listen);
                } else if (parsed.packetType == QuicPacket.TYPE_HANDSHAKE) {
                    processHandshake(handshake, rawData, datagramOffset, parsed, remoteAddr, localAddr, listen);
                } else if (parsed.packetType == QuicPacket.TYPE_0RTT) {
                    process0RttPacket(handshake, rawData, parsed, remoteAddr);
                }
            } catch (Exception e) {
                logger.error("QUIC handshake processing error: " + e.getMessage(), e);
            }

            datagramOffset = nextOffset;
        }
    }

    /* Decrypts and processes an Initial packet. datagramOffset indicates the start position of this packet within rawData and is used for AAD computation in coalesced-packet scenarios. */
    private void processInitial(QuicAsyncChannelHandshake handshake, byte[] rawData, int datagramOffset, QuicPacket.ParsedPacket parsed,//
            SocketAddress remoteAddr, SocketAddress localAddr, QuicListen listen) throws Exception {

        if (quicSoConfig().isSslEnabled()) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] decrypt Initial: hdrLen=" + parsed.headerLength + " payLen=" + parsed.payloadLength + " from " + remoteAddr);
            }
            if (!handshake.decryptLongHeaderPacket(rawData, datagramOffset, parsed, QuicAsyncChannelHandshake.LEVEL_INITIAL)) {
                logger.error("Failed to decrypt Initial packet from " + remoteAddr + " (datagramOffset=" + datagramOffset + ")");
                return;
            }
        } else {
            QuicPacket.ParsedPacket rawParsed = QuicPacket.parseRawLongHeaderPacket(rawData, datagramOffset, rawData.length - datagramOffset);
            if (rawParsed == null) {
                return;
            }
            parsed.payload = rawParsed.payload;
            parsed.packetNumber = rawParsed.packetNumber;
            parsed.pnLength = rawParsed.pnLength;
        }

        // Process.
        if (!handshake.processServerInitial(parsed, remoteAddr)) {
            return;
        }

        // Check whether the handshake has completed; in non-TLS mode it enters ESTABLISHED immediately after Initial.
        if (handshake.isEstablished()) {
            promoteToConnection(handshake, localAddr, remoteAddr, listen);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Decrypts and processes a Handshake packet, such as the client's Finished; if ESTABLISHED has
     * been reached, continue promoting it to a full connection.
     */
    private void processHandshake(QuicAsyncChannelHandshake handshake, byte[] rawData, int datagramOffset, QuicPacket.ParsedPacket parsed,//
            SocketAddress remoteAddr, SocketAddress localAddr, QuicListen listen) throws Exception {
        // Decrypt.
        if (quicSoConfig().isSslEnabled()) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] decrypt Handshake: hdrLen=" + parsed.headerLength + " payLen=" + parsed.payloadLength + " from " + remoteAddr);
            }
            if (!handshake.decryptLongHeaderPacket(rawData, datagramOffset, parsed, QuicAsyncChannelHandshake.LEVEL_HANDSHAKE)) {
                logger.error("Failed to decrypt Handshake packet from " + remoteAddr + " (datagramOffset=" + datagramOffset + ")");
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

        // Process.
        if (!handshake.processServerHandshake(parsed, remoteAddr)) {
            if (handshake.isAborted()) {
                // The client actively sent CONNECTION_CLOSE, so stale handshake entries must be removed immediately.
                this.handshakeMap.values().removeIf(hs -> hs == handshake);
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] handshake aborted (CONNECTION_CLOSE), remote=" + remoteAddr);
                }
            }
            return;
        }

        // Promote to a full connection.
        if (handshake.isEstablished()) {
            promoteToConnection(handshake, localAddr, remoteAddr, listen);
        }
    }

    /**
     * Processes a 0-RTT (Early Data) packet from a session-resuming client, see RFC 9001 §4.9.1;
     * data is buffered first and handled after the handshake completes.
     */
    private void process0RttPacket(QuicAsyncChannelHandshake handshake, byte[] rawData,//
            QuicPacket.ParsedPacket parsed, SocketAddress remoteAddr) {
        // 0-RTT depends on TLS session ticket support.
        if (!quicSoConfig().isSslEnabled()) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] 0-RTT rcv but TLS disabled, ignoring");
            }
            return;
        }

        // For now, only record the 0-RTT attempt. Full 0-RTT support still requires:
        // 1. Persisting session tickets issued by prior connections.
        // 2. Deriving Early Data keys from the pre-shared key.
        // 3. Decrypting with 0-RTT keys.
        // 4. Buffering data until the handshake completes.
        // 5. Providing replay protection.
        if (handshake == null) {
            // The connection has already been promoted to established state, so arriving 0-RTT data no longer has handshake context.
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] 0-RTT rcv on established connection (no handshake context), ignoring");
            }
            return;
        }

        if (this.context.getConfig().isPrintLog()) {
            logger.info("[QUIC] 0-RTT rcv from " + remoteAddr + " len=" + rawData.length + ", deferred to handshake completion");
        }

        // Temporarily buffer the raw 0-RTT data for later processing.
        handshake.buffer0RttData(rawData);
    }

    /**
     * Processes one buffered 0-RTT packet after the handshake completes.
     */
    private void processBuffered0RttPacket(QuicChannelAsync conn, QuicAsyncChannelHandshake handshake, byte[] rawData) {
        try {
            // Parse it as a raw long-header packet; for now only draining plaintext 0-RTT is supported.
            QuicPacket.ParsedPacket parsed = QuicPacket.parseRawLongHeaderPacket(rawData, 0, rawData.length);
            if (parsed == null || parsed.payload == null) {
                logger.debug("Drained 0-RTT packet cannot be parsed as raw long header, skipping");
                return;
            }
            // Anti-replay protection: discard immediately if this packet number has already been processed, see RFC 9001 §8.4.
            if (!handshake.checkAndMarkRtt0Pn(parsed.packetNumber)) {
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] 0-RTT anti-replay: discarding duplicate PN=" + parsed.packetNumber);
                }
                return;
            }
            // Hand the payload frames to the established connection for processing.
            conn.dispatchReceivedFrames(parsed.payload);
            logger.debug("Delivered drained 0-RTT frame PN=" + parsed.packetNumber);
        } catch (Exception e) {
            logger.warn("Failed to process buffered 0-RTT packet: " + e.getMessage());
        }
    }

    /**
     * Creates {@link QuicChannelAsync} and {@link QuicChannel} from a completed handshake and
     * migrates it from the handshake map to the connection map.
     */
    private void promoteToConnection(QuicAsyncChannelHandshake handshake, SocketAddress localAddr,//
            SocketAddress remoteAddr, QuicListen listen) {
        // Build negotiated configuration data.
        QuicInitConfigData initData = handshake.buildInitConfigData(localAddr, remoteAddr);

        // RFC 9000 §7.3 / §18.2: if the peer's transport parameters violated a MUST-level constraint,
        // the handshake records the offending QUIC error code. Refuse promotion rather than serve a
        // connection whose negotiated state is invalid.
        long tpError = handshake.getPeerTransportParamError();
        if (tpError != 0) {
            logger.warn("[QUIC] transport parameter violation (0x" + Long.toHexString(tpError) //
                    + "): " + handshake.getPeerTransportParamErrorReason() + "; rejecting connection from " + remoteAddr);
            this.handshakeMap.values().removeIf(hs -> hs == handshake);
            return;
        }

        QuicSoConfig quicConfig = quicSoConfig();

        try {
            // Create QuicChannelAsync.
            QuicChannelAsync connAsync = new QuicChannelAsync(//
                    initData, this.transport.getChannel(), quicConfig,//
                    this.context, listen.getInitializer(), listen,//
                    handshake);

            // Create QuicChannel (which sets the back-reference) and initialize the pipeline.
            QuicChannel quicChannel = new QuicChannel(connAsync);
            connAsync.getContext().initChannel(quicChannel, true);

            // Register in the connection map under our local CID.
            CidKey localCidKey = new CidKey(handshake.getLocalCid());
            this.connectionMap.put(localCidKey, connAsync);

            // Drain and deliver any buffered 0-RTT early data (RFC 9001 §4.9.1).
            List<byte[]> buffered0Rtt = handshake.drain0RttData();
            if (!buffered0Rtt.isEmpty()) {
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] draining " + buffered0Rtt.size() + " buffered 0-RTT packets after handshake");
                }
                for (byte[] rtt0Data : buffered0Rtt) {
                    processBuffered0RttPacket(connAsync, handshake, rtt0Data);
                }
            }

            // Clean up handshake entries by removing all entries pointing to this handshake.
            this.handshakeMap.values().removeIf(hs -> hs == handshake);

            logger.info("[QUIC] connection established, cid=" + bytesToHex(handshake.getLocalCid()) //
                    + " remote=" + remoteAddr);

            // Notify the connection-established listener, for example to set up HTTP/3 server control streams.
            QuicConnectionListener listener = quicConfig.getConnectionListener();
            if (listener != null) {
                try {
                    listener.onConnectionEstablished(quicChannel);
                } catch (Throwable listenerEx) {
                    logger.error("Connection listener failed: " + listenerEx.getMessage(), listenerEx);
                }
            }
        } catch (Throwable e) {
            logger.error("Failed to create QUIC connection: " + e.getMessage(), e);
            this.handshakeMap.values().removeIf(hs -> hs == handshake);
        }
    }

    /**
     * Processes an inbound Short Header (1-RTT) packet by first looking up the connection by DCID
     * and then dispatching the decrypted payload.
     */
    private void processShortHeaderPacket(SocketAddress remoteAddr, byte[] rawData) {
        QuicSoConfig quicConfig = quicSoConfig();
        int dcidLen = quicConfig.getConnectionIdLength();
        if (rawData.length < 1 + dcidLen) {
            return;
        }

        // Extract the DCID from the Short Header.
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(rawData, 1, dcid, 0, dcidLen);
        CidKey dcidKey = new CidKey(dcid);

        QuicChannelAsync conn = this.connectionMap.get(dcidKey);
        if (conn == null) {
            // Stateless Reset (RFC 9000 §10.3).
            // Unknown DCID — could be Firefox's 1-RTT CONNECTION_CLOSE during a failed handshake
            // Log first two bytes to help identify frame type (CONNECTION_CLOSE = 0x1c)
            if (rawData.length > 1 + dcidLen) {
                int frameTypeByte = rawData[1 + dcidLen] & 0xFF;
                logger.warn("Short-header packet from " + remoteAddr + " with unknown DCID dcid=" + bytesToHex(dcid) + " firstFrameByte=0x" + Integer.toHexString(frameTypeByte) + " len=" + rawData.length + " (no established connection — possible 1-RTT alert during handshake)");
            }
            sendStatelessReset(remoteAddr, rawData);
            return;
        }

        // Connection migration detection (RFC 9000 §9).
        SocketAddress currentRemote = conn.getRemoteAddress();
        if (currentRemote != null && !currentRemote.equals(remoteAddr)) {
            handleConnectionMigration(conn, remoteAddr);
        }

        dispatchAppData(conn, rawData);
    }

    /**
     * Sends a Stateless Reset packet (RFC 9000 §10.3) to a peer carrying an unknown Connection ID,
     * with a 16-byte token at the end of the packet.
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
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] sent StatelessReset to " + remoteAddr + " len=" + maxLen);
            }
        } catch (IOException e) {
            logger.error("Failed to send Stateless Reset: " + e.getMessage());
        }
    }

    // Token validation and Retry (RFC 9000 §8.1).

    /**
     * Detects and handles connection migration by updating the remote address, issuing a new CID,
     * and initiating PATH_CHALLENGE, see RFC 9000 §9.
     */
    private void handleConnectionMigration(QuicChannelAsync conn, SocketAddress newRemoteAddr) {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("[QUIC] migration detected cid=" + bytesToHex(conn.getHandshake().getLocalCid()) + " from " + conn.getRemoteAddress() + " to " + newRemoteAddr);
        }

        // Update the remote address.
        conn.updateRemoteAddress(newRemoteAddr);

        // Activate the anti-amplification limit and register a timeout callback that closes the
        // connection if PATH_CHALLENGE is not answered in time (RFC 9000 §9.3.1).
        conn.getPathValidator().onMigrationStart(() -> {
            logger.warn("PATH_CHALLENGE timed out during migration; closing connection cid=" //
                    + bytesToHex(conn.getHandshake().getLocalCid()));
            conn.closeWithError(QuicErrorCode.NO_VIABLE_PATH, "PATH_CHALLENGE timeout after migration", null);
        });

        // Issue a new local CID and send a NEW_CONNECTION_ID frame so the peer can use the new
        // CID on the new path (RFC 9000 §9.5).
        byte[] newCidFrame = conn.getCidManager().issueNewConnectionId();
        if (newCidFrame != null) {
            conn.sendDataFrame(ByteBuf.wrap(newCidFrame), null);
        }

        // Register all active local CIDs in connectionMap so incoming packets using any of the new
        // CIDs are dispatched to this connection (RFC 9000 §9.5).
        for (byte[] cid : conn.getCidManager().getActiveLocalCids()) {
            this.connectionMap.putIfAbsent(new CidKey(cid), conn);
        }

        // Reset congestion control for the new path (RFC 9000 §9.4).
        conn.getCongestionControl().reset();

        // Initiate path validation via PATH_CHALLENGE (RFC 9000 §8.2.1).
        byte[] challengeFrame = conn.getPathValidator().initiateChallenge();
        if (challengeFrame != null) {
            conn.sendDataFrame(ByteBuf.wrap(challengeFrame), null);
        }
    }

    /**
     * Decrypts a 1-RTT packet and dispatches its payload frames to the connection handler through
     * {@link QuicChannelAsync#dispatchReceivedFrames}.
     */
    private void dispatchAppData(QuicChannelAsync conn, byte[] rawData) {
        conn.checkIdleTimeouts();
        // Account for incoming bytes for anti-amplification (RFC 9000 §9.3.1).
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
                // RFC 9000 §10.3.1: undecryptable short-header datagram whose trailing 16 bytes
                // match a peer Stateless Reset Token causes a silent draining.
                if (conn.matchesStatelessResetToken(rawData)) {
                    conn.enterDrainingSilently("Stateless Reset received (RFC 9000 §10.3.1)");
                }
                return;
            }

            handshake.updateMaxAppPacketNumber(parsed.packetNumber);

            // Dispatch individual QUIC frames to streams, datagrams, and control handlers.
            conn.dispatchReceivedFrames(parsed.payload);
        } catch (Exception e) {
            logger.error("Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            QuicException qe = new QuicException(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            conn.notifyAllChannelsException(qe);
            conn.closeWithError(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), null);
        }
    }

    /**
     * Validates the token carried in an Initial packet, including timestamp freshness
     * (no more than 60 seconds old) and source-address hash matching.
     */
    private boolean validateToken(byte[] token, SocketAddress remoteAddr, byte[] dcid) {
        if (token.length < 8) {
            return false;
        }
        // Extract the timestamp (first 4 bytes, big-endian).
        long timestamp = ((long) (token[0] & 0xFF) << 24) | ((token[1] & 0xFF) << 16) //
                | ((token[2] & 0xFF) << 8) | (token[3] & 0xFF);
        long ageSeconds = System.currentTimeMillis() / 1000 - timestamp;
        if (ageSeconds < 0 || ageSeconds > 60) {
            return false; // Token expired.
        }
        // Validate the address hash (next 4 bytes).
        int expectedHash = remoteAddr.hashCode();
        int tokenHash = ((token[4] & 0xFF) << 24) | ((token[5] & 0xFF) << 16) //
                | ((token[6] & 0xFF) << 8) | (token[7] & 0xFF);
        return tokenHash == expectedHash;
    }

    /**
     * Generates an address-validation token containing the timestamp, address hash, and original DCID.
     */
    private byte[] generateToken(SocketAddress remoteAddr, byte[] originalDcid) {
        // Token format: [4 bytes: timestamp] [4 bytes: addr hash] [N bytes: original DCID].
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
     * Sends a Version Negotiation packet (RFC 9000 §17.2.1) to the remote address, advertising
     * the QUIC versions currently supported.
     */
    private void sendVersionNegotiationPacket(SocketAddress remoteAddr, byte[] clientDcid, byte[] clientScid) {
        int[] supportedVersions = new int[] { QuicVersion.VERSION_1, QuicVersion.VERSION_2 };
        byte[] packet = QuicPacket.buildVersionNegotiationPacket(clientDcid, clientScid, supportedVersions);
        try {
            ByteBuffer buf = ByteBuffer.wrap(packet);
            this.transport.getChannel().send(buf, remoteAddr);
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] sent VersionNegotiation to " + remoteAddr);
            }
        } catch (IOException e) {
            logger.error("Failed to send Version Negotiation: " + e.getMessage());
        }
    }

    /**
     * Sends a Retry packet (RFC 9000 §17.2.5) to the client, containing a new CID chosen by the
     * server and an address-validation token.
     */
    private void sendRetryPacket(SocketAddress remoteAddr, byte[] clientDcid, byte[] clientScid) {
        // Generate a new server CID.
        int cidLen = quicSoConfig().getConnectionIdLength();
        byte[] newServerCid = new byte[cidLen];
        new SecureRandom().nextBytes(newServerCid);

        // Generate the Retry token.
        byte[] retryToken = generateToken(remoteAddr, clientDcid);

        // Build the Retry packet (RFC 9000 §17.2.5).
        // First byte: 1 (long header) | 1 (fixed bit) | 11 (type=Retry) | 0000 (unused)
        QuicVersion version = quicSoConfig().getQuicVersion();
        int versionInt = version == QuicVersion.V1 ? 0x00000001 : 0x6b3343cf;

        int packetLen = 1 + 4 + 1 + (clientScid != null ? clientScid.length : 0) + 1 + newServerCid.length + retryToken.length + 16;
        byte[] packet = new byte[packetLen];
        int pos = 0;

        // First byte: Long Header form (0x80) | Fixed Bit (0x40) | Type (0x30 for Retry) | unused (0x00)
        packet[pos++] = (byte) 0xF0; // 11110000

        // Version (4 bytes).
        packet[pos++] = (byte) (versionInt >> 24);
        packet[pos++] = (byte) (versionInt >> 16);
        packet[pos++] = (byte) (versionInt >> 8);
        packet[pos++] = (byte) versionInt;

        // DCID Length + DCID (set to the client's SCID).
        int dcidLen = clientScid != null ? clientScid.length : 0;
        packet[pos++] = (byte) dcidLen;
        if (dcidLen > 0) {
            System.arraycopy(clientScid, 0, packet, pos, dcidLen);
            pos += dcidLen;
        }

        // SCID Length + SCID (our new CID).
        packet[pos++] = (byte) newServerCid.length;
        System.arraycopy(newServerCid, 0, packet, pos, newServerCid.length);
        pos += newServerCid.length;

        // Retry token.
        System.arraycopy(retryToken, 0, packet, pos, retryToken.length);
        pos += retryToken.length;

        // Retry Integrity Tag (RFC 9001 §5.8, RFC 9369 §3.3.3 for v2).
        // The tag is AEAD_AES_128_GCM over the Retry Pseudo-Packet with a fixed per-version key
        // and nonce. Without this, standards-compliant QUIC clients drop the Retry silently.
        byte[] integrityTag;
        try {
            byte[] retryWithoutTag = new byte[pos];
            System.arraycopy(packet, 0, retryWithoutTag, 0, pos);
            integrityTag = QuicCrypto.computeRetryIntegrityTag(clientDcid, retryWithoutTag, version);
        } catch (Exception e) {
            logger.error("Failed to compute Retry Integrity Tag: " + e.getMessage());
            return;
        }
        System.arraycopy(integrityTag, 0, packet, pos, 16);
        pos += 16;

        try {
            ByteBuffer buf = ByteBuffer.wrap(packet, 0, pos);
            this.transport.getChannel().send(buf, remoteAddr);
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] sent Retry to " + remoteAddr);
            }
        } catch (IOException e) {
            logger.error("Failed to send Retry packet: " + e.getMessage());
        }
    }

    /**
     * Helper type used to wrap byte[] values as ConcurrentHashMap keys.
     */
    static final class CidKey {
        final byte[]      cid;
        private final int hash;

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
