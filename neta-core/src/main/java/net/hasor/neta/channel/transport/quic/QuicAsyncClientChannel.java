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
import java.util.Arrays;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.channel.SoRcvException;
import net.hasor.neta.channel.transport.udp.UdpAsyncClientChannel;

/**
 * Client-side QUIC channel built on top of UDP client transport.
 * <p>
 * It is responsible for the client flow before connection establishment: opening the underlying UDP socket, driving
 * {@link QuicAsyncChannelHandshake}, processing Version Negotiation, Initial, Handshake, and early 1-RTT responses,
 * and promoting the transport layer to {@link QuicChannelAsync}/{@link QuicChannel} only after the handshake reaches
 * the stage where the public connection object can be initialized.
 * <pre>
 *   UDP connect
 *      +--> create QuicAsyncChannelHandshake
 *      +--> send client Initial
 *      +--> process server Initial / Handshake / HANDSHAKE_DONE
 *      +--> build QuicChannelAsync
 *      +--> init public QuicChannel pipeline
 *      +--> complete user future
 * </pre>
 * <p>
 * It is a temporary bootstrap object rather than a long-lived API exposed to the application layer. User code usually obtains the final {@link QuicChannel}, not this class itself.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncClientChannel extends UdpAsyncClientChannel {
    private static final Logger       logger = Logger.getLogger(QuicAsyncClientChannel.class);
    private QuicAsyncChannelHandshake handshake;
    private QuicChannelAsync          connAsync;
    private ProtoInitializer          pendingInitializer;
    private Future<NetChannel>        pendingFuture;

    protected QuicAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, QuicSoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);
    }

    /**
     * Scans the payload for a HANDSHAKE_DONE frame (type 0x1e) while skipping other frame bodies.
     */
    private static boolean containsHandshakeDone(byte[] payload) {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];
            if (frameType == QuicFrameType.HANDSHAKE_DONE) {
                return true;
            }
            // Zero-length frames
            if (frameType == QuicFrameType.PADDING || frameType == QuicFrameType.PING) {
                continue;
            }
            // ACK / ACK_ECN (RFC 9000 §19.3)
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                long[] tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // Largest Acknowledged
                tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // ACK Delay
                tmp = QuicVarInt.decode(payload, pos);
                long rangeCount = tmp[0];
                pos += (int) tmp[1]; // ACK Range Count
                tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // First ACK Range
                for (long i = 0; i < rangeCount; i++) {
                    tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // Gap
                    tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // ACK Range
                }
                if (frameType == QuicFrameType.ACK_ECN) {
                    for (int i = 0; i < 3; i++) {
                        tmp = QuicVarInt.decode(payload, pos);
                        pos += (int) tmp[1]; // ECT(0), ECT(1), ECN-CE
                    }
                }
                continue;
            }
            // NEW_CONNECTION_ID (RFC 9000 §19.15)
            if (frameType == QuicFrameType.NEW_CONNECTION_ID) {
                long[] tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // Sequence Number
                tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // Retire Prior To
                int cidLen = payload[pos++] & 0xFF;
                pos += cidLen + 16; // Connection ID + Stateless Reset Token
                continue;
            }
            // CRYPTO (RFC 9000 §19.6)
            if (frameType == QuicFrameType.CRYPTO) {
                long[] tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // Offset
                tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1] + (int) tmp[0]; // Length + Data
                continue;
            }
            // CONNECTION_CLOSE (RFC 9000 §19.19)
            if (frameType == QuicFrameType.CONNECTION_CLOSE || frameType == QuicFrameType.CONNECTION_CLOSE_APP) {
                long[] tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1]; // Error Code
                if (frameType == QuicFrameType.CONNECTION_CLOSE) {
                    tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // Frame Type
                }
                tmp = QuicVarInt.decode(payload, pos);
                pos += (int) tmp[1] + (int) tmp[0]; // Reason Length + Reason
                continue;
            }
            // Unknown frame type: can't determine length, stop scanning
            break;
        }
        return false;
    }

    private QuicSoConfig quicSoConfig() {
        return (QuicSoConfig) this.soConfig;
    }

    //

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        // ── 1. Connect UDP transport ─────────────────────────────────
        try {
            this.transport.connect(this.remoteAddress);
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
            return;
        }

        // ── 2. Create handshake handler ──────────────────────────────
        QuicSoConfig quicConfig = quicSoConfig();
        this.handshake = new QuicAsyncChannelHandshake(true, quicConfig, this.channel, this.remoteAddress, this.context.getConfig().isPrintLog());
        this.pendingInitializer = initializer;
        this.pendingFuture = future;

        // ── 3. Initiate handshake ────────────────────────────────────
        try {
            this.handshake.initTlsEngine();
            this.handshake.initiateClientHandshake(this.remoteAddress);
        } catch (Throwable e) {
            logger.error("ERROR: QUIC handshake initiation failed, " + e.getMessage(), e);
            future.failed(e);
            return;
        }

        // ── 4. Start receive loop for server responses ───────────────
        this.transport.startReceiveLoop(this::onQuicDatagram,//
                () -> !this.transport.isOpen(), () -> {
                    logger.info("rcv(" + this.channelId + ") close from local.");
                    if (this.connAsync != null) {
                        this.context.notifyChannelClose(this.connAsync.getChannelId(), false);
                    }
                }, (e) -> {
                    SoRcvException err = new SoRcvException(e.getMessage(), e);
                    if (this.connAsync != null) {
                        this.context.notifyRcvChannelException(this.connAsync.getChannelId(), false, err);
                    } else {
                        // Handshake not yet complete — fail the pending future
                        this.pendingFuture.failed(err);
                    }
                });
    }

    /**
     * Handles inbound datagrams during the handshake phase and after connection establishment.
     */
    private void onQuicDatagram(SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        if (data.remaining() < 1) {
            return;
        }

        byte[] rawData = new byte[data.remaining()];
        data.get(rawData);

        // If connection is established, dispatch as 1-RTT data
        if (this.handshake.isEstablished() && this.connAsync != null) {
            dispatchAppData(rawData);
            return;
        }

        // Still in handshake — process server response
        boolean longHeader = QuicPacket.isLongHeader(rawData);
        if (longHeader) {
            processLongHeaderResponse(rawData, remoteAddr);
        } else {
            processShortHeaderResponse(rawData);
        }
    }

    /**
     * Handles Long Header responses from the server during the handshake phase, such as Initial, Handshake, or Version Negotiation.
     */
    private void processLongHeaderResponse(byte[] rawData, SocketAddress remoteAddr) {
        // ── Version Negotiation (RFC 9000 §6) ─────────────────────────────────
        if (QuicPacket.isVersionNegotiation(rawData)) {
            processVersionNegotiation(rawData);
            return;
        }

        // ── Retry (RFC 9000 §17.2.5, RFC 9001 §5.8) ──────────────────────────
        if (QuicPacket.isRetryPacket(rawData)) {
            processRetry(rawData);
            return;
        }

        QuicPacket.ParsedPacket parsed = QuicPacket.parseLongHeader(rawData, 0, rawData.length);
        if (parsed == null) {
            return;
        }

        try {
            if (parsed.packetType == QuicPacket.TYPE_INITIAL) {
                // Decrypt Initial
                if (quicSoConfig().isSslEnabled()) {
                    if (!this.handshake.decryptLongHeaderPacket(rawData, 0, parsed, QuicAsyncChannelHandshake.LEVEL_INITIAL)) {
                        logger.error("Failed to decrypt server Initial");
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

                // Process the server Initial response.
                if (this.handshake.processClientInitialResponse(parsed)) {
                    if (this.handshake.isEstablished()) {
                        completeHandshake();
                    }
                }
            } else if (parsed.packetType == QuicPacket.TYPE_HANDSHAKE) {
                // Decrypt Handshake
                if (quicSoConfig().isSslEnabled()) {
                    if (!this.handshake.decryptLongHeaderPacket(rawData, 0, parsed, QuicAsyncChannelHandshake.LEVEL_HANDSHAKE)) {
                        logger.error("Failed to decrypt server Handshake");
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

                // Process the server Handshake response.
                if (this.handshake.processClientHandshakeResponse(parsed)) {
                    if (this.handshake.isEstablished()) {
                        completeHandshake();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("QUIC client handshake error: " + e.getMessage(), e);
            this.pendingFuture.failed(e);
        }
    }

    /**
     * Handles an incoming Retry packet (RFC 9000 §17.2.5, RFC 9001 §5.8). The integrity tag is
     * validated inside {@link QuicAsyncChannelHandshake#processRetry}; on success the handshake
     * re-derives Initial keys and retransmits the Initial with the attached Retry Token.
     */
    private void processRetry(byte[] rawData) {
        QuicPacket.RetryPacket retry = QuicPacket.parseRetry(rawData, 0, rawData.length);
        if (retry == null) {
            logger.warn("[QUIC] malformed Retry packet, discarded");
            return;
        }
        try {
            this.handshake.processRetry(retry, rawData);
        } catch (Exception e) {
            logger.error("QUIC client Retry processing failed: " + e.getMessage(), e);
            this.pendingFuture.failed(e);
        }
    }

    /**
     * Handles Version Negotiation packets received from the server during the handshake phase (RFC 9000 §6).
     * This method validates the packet, selects the first QUIC version supported by both sides, and re-initiates the handshake with that version.
     * If a VN packet is received after the connection has already been established, it is ignored silently.
     */
    private void processVersionNegotiation(byte[] rawData) {
        // RFC 9000 §6.2: MUST ignore if the connection is already established
        if (this.handshake.isEstablished()) {
            logger.debug("[QUIC-VN] ignoring VN packet on established connection (RFC 9000 §6.2)");
            return;
        }

        // RFC 9000 §6.2: DCID in VN packet MUST match our original Source Connection ID (localCid)
        byte[] vnDcid = QuicPacket.parseVersionNegotiationDcid(rawData);
        if (vnDcid == null || !Arrays.equals(vnDcid, this.handshake.getLocalCid())) {
            logger.warn("[QUIC-VN] discarding VN: DCID does not match our localCid (RFC 9000 §6.2)");
            return;
        }

        // Parse the server's supported version list
        int[] serverVersions = QuicPacket.parseVersionNegotiationVersions(rawData);
        if (serverVersions == null || serverVersions.length == 0) {
            logger.warn("[QUIC-VN] VN packet contains no supported versions, aborting connection");
            this.pendingFuture.failed(new IOException("QUIC Version Negotiation failed: server advertised no versions"));
            return;
        }

        // Select the first version recognised by both sides
        QuicVersion chosen = null;
        for (int v : serverVersions) {
            chosen = QuicVersion.fromVersion(v);
            if (chosen != null) {
                break;
            }
        }
        if (chosen == null) {
            logger.warn("[QUIC-VN] no mutually supported QUIC version found in server's list");
            this.pendingFuture.failed(new IOException("QUIC Version Negotiation: no mutually supported version"));
            return;
        }

        // RFC 9000 §6.2: if chosen version == current version, ignore to prevent infinite retry loops
        QuicSoConfig quicConfig = quicSoConfig();
        if (chosen.getVersion() == quicConfig.getQuicVersion().getVersion()) {
            logger.warn("[QUIC-VN] chosen version 0x" + String.format("%08x", chosen.getVersion()) + " matches current version — ignoring to prevent retry loop (RFC 9000 §6.2)");
            return;
        }

        logger.info("[QUIC-VN] downgrading from 0x" + String.format("%08x", quicConfig.getQuicVersion().getVersion()) + " to 0x" + String.format("%08x", chosen.getVersion()));

        // Re-create a fresh handshake handler with the chosen version and re-send Initial
        // The existing receive loop continues and will process the new handshake responses.
        this.handshake = new QuicAsyncChannelHandshake(true, quicConfig, chosen,//
                this.channel, this.remoteAddress, this.context.getConfig().isPrintLog());
        try {
            this.handshake.initTlsEngine();
            this.handshake.initiateClientHandshake(this.remoteAddress);
        } catch (Exception e) {
            logger.error("[QUIC-VN] failed to re-initiate handshake after version negotiation: " + e.getMessage(), e);
            this.pendingFuture.failed(e);
        }
    }

    /**
     * Handles Short Header (1-RTT) packets during the handshake phase, where the server sends HANDSHAKE_DONE over 1-RTT frames.
     */
    private void processShortHeaderResponse(byte[] rawData) {
        try {
            QuicPacket.ParsedPacket parsed;
            if (quicSoConfig().isSslEnabled()) {
                parsed = this.handshake.decrypt1RttPacket(rawData, 0, rawData.length);
            } else {
                parsed = QuicPacket.parseRawShortHeader(rawData, quicSoConfig().getConnectionIdLength());
            }

            if (parsed == null || parsed.payload == null) {
                return;
            }

            this.handshake.updateMaxAppPacketNumber(parsed.packetNumber);

            // Check for HANDSHAKE_DONE frame in the payload
            if (containsHandshakeDone(parsed.payload)) {
                this.handshake.processHandshakeDone();
                if (this.handshake.isEstablished()) {
                    completeHandshake();
                }
                // After handshake completion, dispatch remaining frames in this packet
                // (HANDSHAKE_DONE is a zero-length frame and will be harmlessly skipped by dispatchReceivedFrames)
                if (this.connAsync != null) {
                    this.connAsync.dispatchReceivedFrames(parsed.payload);
                }
                return;
            }

            // If already established (non-TLS transitions before HANDSHAKE_DONE), dispatch as application data
            if (this.handshake.isEstablished() && this.connAsync != null) {
                this.connAsync.dispatchReceivedFrames(parsed.payload);
            }
        } catch (Exception e) {
            logger.error("QUIC client 1-RTT processing error: " + e.getMessage(), e);
        }
    }

    /**
     * Completes the QUIC handshake by creating {@link QuicChannelAsync} and {@link QuicChannel}, then resolves the pending future.
     */
    private void completeHandshake() {
        if (this.connAsync != null) {
            return; // already completed
        }

        QuicSoConfig quicConfig = quicSoConfig();
        SocketAddress localAddr;
        try {
            localAddr = this.transport.getLocalAddress();
        } catch (IOException e) {
            this.pendingFuture.failed(e);
            return;
        }

        QuicInitConfigData initData = this.handshake.buildInitConfigData(localAddr, this.remoteAddress);

        // RFC 9000 §7.3 / §18.2 receive-side validation: if the server's transport parameters
        // violated a MUST-level constraint, fail the connection with the recorded QUIC error code.
        long tpError = this.handshake.getPeerTransportParamError();
        if (tpError != 0) {
            String reason = this.handshake.getPeerTransportParamErrorReason();
            logger.warn("[QUIC] server transport parameter violation (0x" + Long.toHexString(tpError) + "): " + reason);
            this.pendingFuture.failed(new IOException("QUIC transport parameter error 0x" + Long.toHexString(tpError) + ": " + reason));
            return;
        }

        try {
            // Create QuicChannelAsync (client mode: listen=null)
            this.connAsync = new QuicChannelAsync(//
                    initData, this.channel, quicConfig,//
                    this.context, this.pendingInitializer, null,//
                    this.handshake);

            // Create QuicChannel (sets back-reference) and initialize pipeline
            QuicChannel quicChannel = new QuicChannel(this.connAsync);
            this.connAsync.getContext().initChannel(quicChannel, true);

            this.pendingFuture.completed(quicChannel);
            logger.info("QUIC connection established (client mode)");
        } catch (Throwable e) {
            logger.error("Failed to create QUIC connection: " + e.getMessage(), e);
            this.pendingFuture.failed(e);
        }
    }

    /**
     * Dispatches decrypted 1-RTT data to the protocol-processing pipeline of the established connection.
     */
    private void dispatchAppData(byte[] rawData) {
        this.connAsync.checkIdleTimeouts();
        try {
            QuicPacket.ParsedPacket parsed;
            if (quicSoConfig().isSslEnabled()) {
                parsed = this.handshake.decrypt1RttPacket(rawData, 0, rawData.length);
            } else {
                parsed = QuicPacket.parseRawShortHeader(rawData, quicSoConfig().getConnectionIdLength());
            }

            if (parsed == null || parsed.payload == null) {
                // RFC 9000 §10.3.1: if decryption fails, check whether the trailing 16 bytes
                // match a peer Stateless Reset Token and, if so, enter the draining period.
                if (this.connAsync.matchesStatelessResetToken(rawData)) {
                    this.connAsync.enterDrainingSilently("Stateless Reset received (RFC 9000 §10.3.1)");
                }
                return;
            }

            this.handshake.updateMaxAppPacketNumber(parsed.packetNumber);

            // Dispatch individual QUIC frames to streams, datagrams, and control handlers
            this.connAsync.dispatchReceivedFrames(parsed.payload);
        } catch (Exception e) {
            logger.error("Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            QuicException qe = new QuicException(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), e);
            this.connAsync.notifyAllChannelsException(qe);
            this.connAsync.closeWithError(QuicErrorCode.INTERNAL_ERROR, "Failed to dispatch 1-RTT data: " + e.getMessage(), null);
        }
    }
}
