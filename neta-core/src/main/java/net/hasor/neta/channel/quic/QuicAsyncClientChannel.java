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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.channel.SoRcvException;
import net.hasor.neta.channel.udp.UdpAsyncClientChannel;

/**
 * QUIC client channel extending {@link UdpAsyncClientChannel}.
 * <p>
 * Overrides {@link #connectTo} to initiate a QUIC handshake over the UDP transport.
 * The {@link Future} passed to {@code connectTo} is completed only after the QUIC
 * handshake reaches the ESTABLISHED state.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncClientChannel extends UdpAsyncClientChannel {
    private static final Logger                    logger = Logger.getLogger(QuicAsyncClientChannel.class);
    private              QuicAsyncChannelHandshake handshake;
    private              QuicChannelAsync          connAsync;
    private              ProtoInitializer          pendingInitializer;
    private              Future<NetChannel>        pendingFuture;

    protected QuicAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, QuicSoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);
    }

    /**
     * Checks if the payload contains a HANDSHAKE_DONE frame (type 0x1e).
     * Scans through known frame types, skipping their bodies to find HANDSHAKE_DONE.
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

    // ── Connect override ───────────────────────────────────────────────

    private QuicSoConfig quicSoConfig() {
        return (QuicSoConfig) this.soConfig;
    }

    // ── QUIC datagram processing ───────────────────────────────────────

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
        this.handshake = new QuicAsyncChannelHandshake(true, quicConfig, this.channel, this.remoteAddress);
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
        this.transport.startReceiveLoop(//
                (remoteAddr, data) -> this.onQuicDatagram(remoteAddr, data), //
                () -> {
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

    /** Processes incoming datagrams during handshake and after connection establishment. */
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

    /** Processes a Long Header response from the server during handshake (Initial with ServerHello, or Handshake with server messages). */
    private void processLongHeaderResponse(byte[] rawData, SocketAddress remoteAddr) {
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

                // Process server's Initial response
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

                // Process server's Handshake response
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

    /** Processes a Short Header (1-RTT) packet during handshake. The server sends HANDSHAKE_DONE as a 1-RTT frame. */
    private void processShortHeaderResponse(byte[] rawData) {
        try {
            QuicPacket.ParsedPacket parsed;
            if (quicSoConfig().isSslEnabled()) {
                parsed = this.handshake.decrypt1RttPacket(rawData, 0, rawData.length);
            } else {
                parsed = QuicAsyncServerChannel.parseRawShortHeader(rawData, quicSoConfig().getConnectionIdLength());
            }

            if (parsed == null || parsed.payload == null) {
                return;
            }

            this.handshake.updateLargestAppPn(parsed.packetNumber);

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

    /** Completes the QUIC handshake by creating the {@link QuicChannelAsync} and {@link QuicChannel}, then fulfills the pending future. */
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

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Dispatches decrypted 1-RTT data to the established connection's protocol pipeline. */
    private void dispatchAppData(byte[] rawData) {
        this.connAsync.checkIdleTimeouts();
        try {
            QuicPacket.ParsedPacket parsed;
            if (quicSoConfig().isSslEnabled()) {
                parsed = this.handshake.decrypt1RttPacket(rawData, 0, rawData.length);
            } else {
                parsed = QuicAsyncServerChannel.parseRawShortHeader(rawData, quicSoConfig().getConnectionIdLength());
            }

            if (parsed == null || parsed.payload == null) {
                return;
            }

            this.handshake.updateLargestAppPn(parsed.packetNumber);

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
