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
import java.nio.channels.DatagramChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * Connection-level {@link AsyncChannel} for QUIC, responsible for managing
 * all sub-channels (streams and datagrams) under a single QUIC connection.
 * <p>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicChannelAsync implements AsyncChannel {
    private static final Logger                           logger = Logger.getLogger(QuicChannelAsync.class);
    private final        long                             channelId;
    private final        SocketAddress                    localAddress;
    private final        boolean                          clientMode;
    private final        QuicSoConfig                     quicSoConfig;
    //
    private final        DatagramChannel                  ownerUdp;
    private final        NetListen                        forListen;
    private final        ProtoInitializer                 initializer;
    private final        SoContextService                 context;
    private final        QuicAsyncChannelHandshake        handshake;
    //
    private final        AtomicBoolean                    closed;
    //
    private final        long                             negotiationDatagramMaxData; // Last time (in milliseconds) any packet was sent or received on this connection.
    private volatile     long                             lastActivityTime;
    private volatile     long                             negotiationMaxData;
    // stream for bidi
    private volatile     long                             peerMaxStreamsBidi;
    private volatile     long                             nextRemoteBidiStreamId;
    private volatile     long                             peerStreamMaxDataBidiLocal;
    private volatile     long                             peerStreamMaxDataBidiRemote;
    // stream for uni
    private volatile     long                             peerMaxStreamsUni;
    private volatile     long                             nextRemoteUniStreamId;
    private volatile     long                             peerStreamMaxDataUni;
    private              QuicChannel                      quicChannel;
    private volatile     QuicDatagramChannel              datagramChannel;
    //
    private final        Set<Long>                        streamIds;
    private final        Map<Long, QuicStreamChannel>     streamMap;
    //
    // ── ACK, Loss Detection, Congestion Control, Flow Control
    private final        QuicAckTracker                   ackTracker;
    private final        QuicSentPacketTracker            sentPacketTracker;
    private final        QuicCongestionControl            congestionControl;
    private final        QuicFlowControl                  flowControl;
    //
    // ── Stream Reassembly
    private final        Map<Long, QuicStreamReassembler> streamReassemblers;
    //
    // ── CRYPTO frame reassembly for post-handshake messages (1-RTT) ──
    private final        QuicStreamReassembler            cryptoReassembler;
    //
    // ── Connection ID Management
    private final        QuicConnectionIdManager          cidManager;
    //
    // ── Path Validation
    private final        QuicPathValidator                pathValidator;
    private volatile     SocketAddress                    remoteAddress;
    private final        Map<Long, PendingPing>           pendingPings;

    public QuicChannelAsync(QuicInitConfigData handshakeData, DatagramChannel ownerUdp, QuicSoConfig soConfig,//
            SoContextService context, ProtoInitializer initializer, QuicListen listen,//
            QuicAsyncChannelHandshake handshake) {
        this.channelId = context.nextID();
        this.localAddress = handshakeData.getLocalAddr();
        this.remoteAddress = handshakeData.getRemoteAddr();
        this.clientMode = listen == null;
        this.quicSoConfig = soConfig;
        this.ownerUdp = ownerUdp;
        this.forListen = listen;
        this.initializer = initializer;
        this.context = context;
        this.handshake = handshake;
        this.closed = new AtomicBoolean(false);
        this.lastActivityTime = System.currentTimeMillis();
        //
        this.negotiationMaxData = Math.min(handshakeData.getPeerMaxData(), soConfig.getTpInitialFrameMaxData());
        this.negotiationDatagramMaxData = Math.min(handshakeData.getDatagramMaxDataSize(), soConfig.getTpInitialDatagramFrameMaxData());
        this.peerMaxStreamsBidi = handshakeData.getPeerMaxStreamsBidi();
        this.peerMaxStreamsUni = handshakeData.getPeerMaxStreamsUni();
        this.peerStreamMaxDataBidiLocal = handshakeData.getPeerStreamMaxDataBidiLocal();
        this.peerStreamMaxDataBidiRemote = handshakeData.getPeerStreamMaxDataBidiRemote();
        this.peerStreamMaxDataUni = handshakeData.getPeerStreamMaxDataUni();
        this.nextRemoteBidiStreamId = this.clientMode ? 1 : 0; // server opens 1,5,9…(bidi) and 3,7,11…(uni)
        this.nextRemoteUniStreamId = this.clientMode ? 3 : 2;  // client opens 0,4,8…(bidi) and 2,6,10…(uni)
        //
        this.streamIds = ConcurrentHashMap.newKeySet();
        this.streamMap = new ConcurrentHashMap<>();
        //
        // ── Initialize ACK, Loss Detection, Congestion Control, Flow Control ──
        this.ackTracker = new QuicAckTracker();
        this.sentPacketTracker = new QuicSentPacketTracker();
        this.congestionControl = new QuicCongestionControl();
        this.flowControl = new QuicFlowControl(soConfig.getTpInitialFrameMaxData());
        this.streamReassemblers = new ConcurrentHashMap<>();
        this.cryptoReassembler = new QuicStreamReassembler();
        this.cidManager = new QuicConnectionIdManager(handshake.getLocalCid(), handshake.getRemoteCid(), soConfig.getConnectionIdLength());
        this.pathValidator = new QuicPathValidator();
        this.pendingPings = new ConcurrentHashMap<>();
    }

    /** Skips an ACK or ACK_ECN frame body, returning the new position. Returns {@code -1} on parse error. */
    private static int skipAckFrame(byte[] data, int pos, int frameType) {
        if (pos >= data.length) {
            return -1;
        }
        long[] tmp = QuicVarInt.decode(data, pos);
        pos += (int) tmp[1]; // Largest Acknowledged
        tmp = QuicVarInt.decode(data, pos);
        pos += (int) tmp[1]; // ACK Delay
        tmp = QuicVarInt.decode(data, pos);
        long rangeCount = tmp[0];
        pos += (int) tmp[1]; // ACK Range Count
        tmp = QuicVarInt.decode(data, pos);
        pos += (int) tmp[1]; // First ACK Range
        for (long i = 0; i < rangeCount; i++) {
            tmp = QuicVarInt.decode(data, pos);
            pos += (int) tmp[1]; // Gap
            tmp = QuicVarInt.decode(data, pos);
            pos += (int) tmp[1]; // ACK Range
        }
        if (frameType == QuicFrameType.ACK_ECN) {
            for (int i = 0; i < 3; i++) {
                tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // ECT(0), ECT(1), ECN-CE
            }
        }
        return pos;
    }

    /** Returns {@code true} if the payload contains ack-eliciting frames (anything other than ACK, PADDING). */
    private static boolean containsAckElicitingFrames(byte[] payload) {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int ft = (int) typeResult[0];
            return ft != QuicFrameType.ACK && ft != QuicFrameType.ACK_ECN && ft != QuicFrameType.PADDING;
            // Skip the frame body to check next frame
            // For simplicity, just return false for ACK-only and true otherwise
        }
        return false;
    }

    /** Sets the back-reference to the connection-level QuicChannel. Called by {@link QuicChannel} constructor. */
    void setQuicChannel(QuicChannel quicChannel) {
        this.quicChannel = quicChannel;
    }

    /** Returns the handshake handler for this connection. */
    QuicAsyncChannelHandshake getHandshake() {
        return this.handshake;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public QuicSoConfig getSoConfig() {
        return this.quicSoConfig;
    }

    public boolean isClientMode() {
        return this.clientMode;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    /** Updates the remote address after a connection migration is detected. */
    public void updateRemoteAddress(SocketAddress newRemoteAddr) {
        this.remoteAddress = newRemoteAddr;
    }

    public SoContextService getContext() {
        return this.context;
    }

    public ProtoInitializer getInitializer() {
        return this.initializer;
    }

    public NetListen getForListen() {
        return this.forListen;
    }

    public long getNegotiationMaxData() {
        return this.negotiationMaxData;
    }

    public long getPeerDatagramMaxData() {
        return this.negotiationDatagramMaxData;
    }

    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    /** Returns the last activity time of this connection in epoch milliseconds. */
    long getLastActivityTime() {
        return this.lastActivityTime;
    }

    /** Touches the activity timestamp (called when data passes through a stream or datagram channel). */
    void touchActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /** Returns the ACK tracker for this connection. */
    QuicAckTracker getAckTracker() {
        return this.ackTracker;
    }

    /** Returns the sent packet tracker for loss detection. */
    QuicSentPacketTracker getSentPacketTracker() {
        return this.sentPacketTracker;
    }

    /** Returns the congestion controller. */
    QuicCongestionControl getCongestionControl() {
        return this.congestionControl;
    }

    /** Returns the flow control tracker. */
    QuicFlowControl getFlowControl() {
        return this.flowControl;
    }

    /** Returns the connection ID manager. */
    QuicConnectionIdManager getCidManager() {
        return this.cidManager;
    }

    /** Returns the path validator. */
    QuicPathValidator getPathValidator() {
        return this.pathValidator;
    }

    //

    public void updateGlobalMaxDataSize(long globalMaxDataSize) {
        this.negotiationMaxData = Math.max(this.negotiationMaxData, globalMaxDataSize);
    }

    public void updateInitConfigData(QuicInitConfigData initConfigData) {
        if (this.closed.get() || initConfigData == null) {
            return;
        }

        if (initConfigData.getPeerMaxData() > 0) {
            this.negotiationMaxData = Math.max(this.negotiationMaxData, initConfigData.getPeerMaxData());
        }
        if (initConfigData.getPeerMaxStreamsBidi() > 0) {
            this.peerMaxStreamsBidi = Math.max(this.peerMaxStreamsBidi, initConfigData.getPeerMaxStreamsBidi());
        }
        if (initConfigData.getPeerMaxStreamsUni() > 0) {
            this.peerMaxStreamsUni = Math.max(this.peerMaxStreamsUni, initConfigData.getPeerMaxStreamsUni());
        }
        if (initConfigData.getPeerStreamMaxDataBidiRemote() > 0) {
            this.peerStreamMaxDataBidiRemote = Math.max(this.peerStreamMaxDataBidiRemote, initConfigData.getPeerStreamMaxDataBidiRemote());
        }
        if (initConfigData.getPeerStreamMaxDataBidiLocal() > 0) {
            this.peerStreamMaxDataBidiLocal = Math.max(this.peerStreamMaxDataBidiLocal, initConfigData.getPeerStreamMaxDataBidiLocal());
        }
        if (initConfigData.getPeerStreamMaxDataUni() > 0) {
            this.peerStreamMaxDataUni = Math.max(this.peerStreamMaxDataUni, initConfigData.getPeerStreamMaxDataUni());
        }
        // datagramMaxDataSize is a Transport Parameter fixed at handshake time (RFC 9221 §3); not updated here.
    }

    public Set<Long> getStreamIds() {
        return Collections.unmodifiableSet(this.streamIds);
    }

    public QuicStreamChannel findStream(long streamId) {
        return this.streamMap.getOrDefault(streamId, null);
    }

    public QuicDatagramChannel onlyGetDatagramChannel() {
        return this.datagramChannel;
    }

    /** Creates a new QUIC stream channel with the given stream ID. */
    public Future<QuicStreamChannel> newStreamChannel(long streamId) {
        if (this.closed.get()) {
            return BasicFuture.buildFailed(new SoCloseException("Channel is closed"));
        }

        BasicFuture<QuicStreamChannel> future = new BasicFuture<>();
        String streamLimitViolation = null;

        synchronized (this.streamMap) {
            try {
                // ── 1. Basic validation ──────────────────────────────────────
                if (streamId < 0) {
                    future.failed(new IllegalArgumentException("Stream ID must be non-negative: " + streamId));
                    return future;
                }
                if (this.quicChannel == null) {
                    future.failed(new IllegalStateException("QuicChannel not yet created; handshake may not be complete"));
                    return future;
                }
                if (this.streamMap.containsKey(streamId)) {
                    future.failed(new IllegalStateException("Stream " + streamId + " already exists"));
                    return future;
                }

                // ── 2. Decode stream type (RFC 9000 §2.1) ───────────────────
                boolean clientInitiated = (streamId & 0x01) == 0;
                boolean locallyInitiated = (this.clientMode == clientInitiated);
                boolean bidi = (streamId & 0x02) == 0;

                // ── 3. Stream limit check (RFC 9000 §4.6) ───────────────────
                if (!locallyInitiated) {
                    long ourLimit = bidi ? this.quicSoConfig.getTpInitialMaxStreamsBidi() : this.quicSoConfig.getTpInitialMaxStreamsUni();
                    long streamIndex = streamId / 4;  // stream "ordinal" within its type
                    if (streamIndex >= ourLimit) {
                        // RFC 9000 §4.6: receiving a stream ID beyond advertised MAX_STREAMS is a *connection error* (not stream error).
                        streamLimitViolation = "STREAM_LIMIT_ERROR: remote stream index " + streamIndex//
                                + " exceeds local max_streams=" + ourLimit + " (streamId=" + streamId + ")";
                        future.failed(new IllegalStateException(streamLimitViolation));
                    } else {
                        // RFC 9000 §2.1: skipped stream IDs consume peer's MAX_STREAMS credit but will never carry data.
                        if (bidi) {
                            this.nextRemoteBidiStreamId = Math.max(this.nextRemoteBidiStreamId, streamId + 4);
                        } else {
                            this.nextRemoteUniStreamId = Math.max(this.nextRemoteUniStreamId, streamId + 4);
                        }
                        // ── 4. Create and register the stream ────────────────────────
                        future.completed(doCreateStream(streamId));
                    }
                } else {
                    // ── 4. Create and register the stream (locally initiated) ─────────
                    future.completed(doCreateStream(streamId));
                }
            } catch (Throwable e) {
                future.failed(e);
            }
        }

        // Network I/O (CONNECTION_CLOSE frame) must not be performed while holding the streamMap lock.
        if (streamLimitViolation != null) {
            this.closeWithError(QuicErrorCode.STREAM_LIMIT_ERROR, streamLimitViolation, null);
        }
        return future;
    }

    //

    /** Internal helper: creates a single stream channel, initializes its protocol pipeline, and registers it in the tracking maps. */
    private QuicStreamChannel doCreateStream(long streamId) throws Throwable {
        if (this.closed.get()) {
            throw new SoCloseException("Channel is closed");
        }

        boolean clientInitiated = (streamId & 0x01) == 0;
        boolean localInitiated = (this.clientMode == clientInitiated);
        boolean bidi = (streamId & 0x02) == 0;

        long streamMaxData;
        if (bidi) {
            // Both sides can send on a bidi stream.
            streamMaxData = localInitiated ? this.peerStreamMaxDataBidiRemote  // peer limits our sends on locally-initiated bidi
                    : this.quicSoConfig.getTpInitialMaxStreamDataBidiRemote(); // our receive limit for peer-initiated bidi
        } else {
            // Uni-directional: only the initiator sends.
            streamMaxData = localInitiated ? this.peerStreamMaxDataUni         // peer limits our sends on locally-initiated uni
                    : this.quicSoConfig.getTpInitialMaxStreamDataUni();        // our receive limit for peer-initiated uni
        }

        // ── Create async channel + stream channel ────────────────────────
        long channelId = this.context.nextID();
        NetMonitor monitor = new NetMonitor();
        QuicStreamChannelAsync streamAsync = new QuicStreamChannelAsync(//
                channelId, streamId, this.quicChannel, this.context);
        QuicStreamChannel streamCh = new QuicStreamChannel(                    //
                channelId, streamId, monitor, this.forListen, this.initializer,//
                streamAsync, this.context, this.quicChannel, streamMaxData);

        // ── Initialize pipeline (ProtoInitializer → onInit → onActive) ──
        this.context.initChannel(streamCh, true);

        // ── Register in tracking maps ────────────────────────────────────
        this.streamMap.put(streamId, streamCh);
        this.streamIds.add(streamId);

        logger.info("Created QUIC stream " + streamId     //
                + (bidi ? " (bidi)" : " (uni)")              //
                + (localInitiated ? " [local]" : " [remote]")//
                + " maxData=" + streamMaxData);
        return streamCh;
    }

    public Future<QuicDatagramChannel> getOrCreateDatagramChannel() {
        if (this.closed.get()) {
            return BasicFuture.buildFailed(new SoCloseException("Channel is closed"));
        }

        // channel already exists (volatile read, no lock needed)
        if (this.datagramChannel != null) {
            return BasicFuture.buildCompleted(this.datagramChannel);
        }

        BasicFuture<QuicDatagramChannel> future = new BasicFuture<>();
        try {
            // ── Guard checks ──────────────────────────────────────────────
            if (this.quicSoConfig.isDisableDatagram()) {
                future.failed(new IllegalStateException("DATAGRAM channel is disabled by local configuration"));
                return future;
            }
            // RFC 9221 §3: both peers must advertise max_datagram_frame_size > 0 to enable datagrams.
            if (this.negotiationDatagramMaxData == 0) {
                future.failed(new IllegalStateException("DATAGRAM frames not supported: peer did not advertise max_datagram_frame_size > 0"));
                return future;
            }
            if (this.quicChannel == null) {
                future.failed(new IllegalStateException("QuicChannel not yet created; handshake may not be complete"));
                return future;
            }

            // ── Double-checked locking ────────────────────────────────────
            synchronized (this) {
                if (this.datagramChannel != null) {
                    return BasicFuture.buildCompleted(this.datagramChannel);
                }
                long chId = this.context.nextID();
                NetMonitor monitor = new NetMonitor();
                QuicDatagramChannelAsync asyncCh = new QuicDatagramChannelAsync(//
                        chId, this.quicChannel, this.context);
                QuicDatagramChannel datagramCh = new QuicDatagramChannel(//
                        chId, monitor, this.forListen, this.initializer, asyncCh, this.context, this.quicChannel);
                this.context.initChannel(datagramCh, true);
                this.datagramChannel = datagramCh;  // volatile write — visible to all threads
            }
            future.completed(this.datagramChannel);
        } catch (Throwable e) {
            future.failed(e);
        }
        return future;
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Channels do not support connectTo.");
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && this.ownerUdp.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            // Client-side: ownerUdp is dedicated to this single QUIC connection.
            // Server-side: ownerUdp is the shared server DatagramChannel managed by NetListen/UdpAsyncServerChannel.
            if (this.clientMode) {
                IOUtils.closeQuietly(this.ownerUdp);
            }
            closeAllStreams();
        }
    }

    private Future<?> closeAllStreams() {
        final QuicDatagramChannel localDatagramChannel;
        final QuicStreamChannel[] localStreams;

        synchronized (this.streamMap) {
            localDatagramChannel = this.datagramChannel;
            localStreams = this.streamMap.values().toArray(new QuicStreamChannel[0]);

            this.streamMap.clear();
            this.streamIds.clear();
            this.datagramChannel = null;
        }

        // ── Phase 1: close connection-level QuicChannel ──────────────────
        // ── Phase 2: close each sub-channel in its own task (concurrent) ─
        return context.submitSoTask(new CloseQuicChannelTask(this.quicChannel), this).onFinal(f -> {
            for (QuicStreamChannel stream : localStreams) {
                context.submitSoTask(new CloseQuicChannelTask(stream), stream);
            }
            if (localDatagramChannel != null) {
                context.submitSoTask(new CloseQuicChannelTask(localDatagramChannel), localDatagramChannel);
            }
        });
    }

    //

    public void closeWithError(long errorCode, String reason, BasicFuture<QuicChannel> future) {
        if (reason == null) {
            reason = "";
        }

        // Propagate exception to all open sub-channels through the pipeline
        QuicConnectionCloseException ex = new QuicConnectionCloseException(errorCode, reason);
        notifyAllChannelsException(ex);

        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.CONNECTION_CLOSE);
        byte[] errorCodeBytes = QuicVarInt.encode(errorCode);
        byte[] frameTypeBytes = QuicVarInt.encode(0); // triggering frame type unknown
        byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] reasonLenBytes = QuicVarInt.encode(reasonBytes.length);

        int totalLen = typeBytes.length + errorCodeBytes.length + frameTypeBytes.length + reasonLenBytes.length + reasonBytes.length;
        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(errorCodeBytes, 0, frame, pos, errorCodeBytes.length);
        pos += errorCodeBytes.length;
        System.arraycopy(frameTypeBytes, 0, frame, pos, frameTypeBytes.length);
        pos += frameTypeBytes.length;
        System.arraycopy(reasonLenBytes, 0, frame, pos, reasonLenBytes.length);
        pos += reasonLenBytes.length;
        if (reasonBytes.length > 0) {
            System.arraycopy(reasonBytes, 0, frame, pos, reasonBytes.length);
        }

        future = future == null ? new BasicFuture<>() : future;
        future.onFinal(f -> closeAllStreams());
        this.sendDataFrame(ByteBuf.wrap(frame), future);
    }

    //

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        throw new UnsupportedOperationException("use QuicDatagramChannel or QuicStreamChannelAsync to write.");
    }

    /**
     * Wraps the given QUIC frame(s) into a 1-RTT Short Header packet and sends it over the underlying UDP channel.
     * If TLS is enabled, the packet is encrypted using application-level keys derived during the handshake.
     */
    public int sendDataFrame(ByteBuf frame, BasicFuture<QuicChannel> future) {
        if (this.closed.get()) {
            if (future != null) {
                future.failed(new SoCloseException("Channel is closed"));
            }
            return 0;
        }
        if (this.handshake == null || !this.handshake.isEstablished()) {
            if (future != null) {
                future.failed(new IllegalStateException("Handshake not yet established"));
            }
            return 0;
        }

        byte[] payload = new byte[frame.readableBytes()];
        frame.readBytes(payload, 0, payload.length);

        // ── Congestion control: check if we can send ────────────────
        long bytesInFlight = this.sentPacketTracker.getBytesInFlight();
        if (!this.congestionControl.canSend(bytesInFlight)) {
            logger.warn("Congestion window full, cwnd=" + this.congestionControl.getCwnd() + ", inFlight=" + bytesInFlight);
            // Still send (don't drop), but log the congestion event
        }

        // ── Append ACK frame if needed ──────────────────────────────
        byte[] ackFrame = null;
        if (this.ackTracker.shouldSendAck()) {
            ackFrame = this.ackTracker.generateAckFrame();
        }
        byte[] combinedPayload;
        if (ackFrame != null) {
            combinedPayload = new byte[ackFrame.length + payload.length];
            System.arraycopy(ackFrame, 0, combinedPayload, 0, ackFrame.length);
            System.arraycopy(payload, 0, combinedPayload, ackFrame.length, payload.length);
        } else {
            combinedPayload = payload;
        }

        try {
            byte[] packet = this.handshake.build1RttPacket(combinedPayload);
            long pn = this.handshake.getLargestAppPn(); // last used PN
            // Anti-amplification (RFC 9000 §9.3.1): on an unvalidated path the server MUST NOT
            // send more than 3x the bytes it has received.  Drop silently if over limit.
            if (!this.pathValidator.canSendBytes(packet.length)) {
                logger.debug("Anti-amplification limit reached; dropping outgoing packet (" + packet.length + " bytes)");
                if (future != null) {
                    future.completed(this.quicChannel);
                }
                return 0;
            }
            // Track sent packet for loss detection (payload is ack-eliciting if it contains non-ACK frames)
            boolean ackEliciting = containsAckElicitingFrames(payload);
            this.sentPacketTracker.onPacketSent(pn, combinedPayload, packet.length, ackEliciting);
            int sent = this.handshake.sendPacket(packet);
            this.pathValidator.recordOutgoing(sent);
            this.lastActivityTime = System.currentTimeMillis();
            if (future != null) {
                future.completed(this.quicChannel);
            }
            return sent;
        } catch (Exception e) {
            logger.error("sendDataFrame failed: " + e.getMessage(), e);
            QuicException qe = new QuicException(QuicErrorCode.INTERNAL_ERROR, "sendDataFrame failed: " + e.getMessage(), e);
            notifyAllChannelsException(qe);
            this.closeWithError(QuicErrorCode.INTERNAL_ERROR, "sendDataFrame failed: " + e.getMessage(), null);
            if (future != null) {
                future.failed(e);
            }
            return 0;
        }
    }

    /**
     * Sends a PING frame and returns a {@link Future} that completes with the RTT in milliseconds
     * when the peer's ACK is received.
     * @param timeoutMs positive value to enable timeout; 0 = wait indefinitely
     */
    Future<Long> sendPingRtt(long timeoutMs) {
        BasicFuture<Long> future = new BasicFuture<>();
        if (this.closed.get() || this.handshake == null || !this.handshake.isEstablished()) {
            future.failed(new IllegalStateException("Connection not established"));
            return future;
        }
        ByteBuf frame = ByteBuf.wrap(QuicVarInt.encode(QuicFrameType.PING));
        long sentTime = System.currentTimeMillis();
        int sent = sendDataFrame(frame, null);
        if (sent > 0) {
            long pn = this.handshake.getLargestAppPn();
            this.pendingPings.put(pn, new PendingPing(future, sentTime));
            if (timeoutMs > 0) {
                this.context.submitSoTask(new SoDelayTask((int) timeoutMs), this).onFinal(f -> {
                    PendingPing pp = this.pendingPings.remove(pn);
                    if (pp != null) {
                        pp.future.failed(new TimeoutException("PING timeout after " + timeoutMs + "ms"));
                    }
                });
            }
        } else {
            future.failed(new IOException("Failed to send PING frame"));
        }
        return future;
    }

    /**
     * Parses QUIC frames from a decrypted 1-RTT packet payload and dispatches
     * each frame to its appropriate handler: STREAM data to {@link QuicStreamChannel},
     * DATAGRAM data to {@link QuicDatagramChannel}, and control frames to internal state.
     * <p>Called by {@code QuicAsyncServerChannel} and {@code QuicAsyncClientChannel}
     * after decrypting a Short Header (1-RTT) packet.
     */
    void dispatchReceivedFrames(byte[] payload) {
        this.lastActivityTime = System.currentTimeMillis();

        // Determine if this packet is ack-eliciting (for ACK generation)
        boolean packetAckEliciting = false;

        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];

            // ── Zero-length frames ──────────────────────────────────────
            if (frameType == QuicFrameType.PADDING) {
                continue;
            }
            if (frameType == QuicFrameType.PING) {
                packetAckEliciting = true;
                continue;
            }
            if (frameType == QuicFrameType.HANDSHAKE_DONE) {
                packetAckEliciting = true;
                continue; // already processed during handshake phase
            }

            // ── ACK / ACK_ECN (RFC 9000 §19.3) ─────────────────────────
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                int ackStartPos = pos;
                // Parse ACK ranges and process for loss detection
                List<long[]> ackedRanges = QuicAckTracker.parseAckRanges(payload, pos);
                List<byte[]> lostPayloads = this.sentPacketTracker.onAckReceived(ackedRanges);
                // Process congestion control for acked packets
                long ackedBytes = 0;
                long largestAckedPn = -1;
                for (long[] range : ackedRanges) {
                    ackedBytes += (range[1] - range[0] + 1) * QuicCongestionControl.MAX_DATAGRAM_SIZE;
                    if (range[1] > largestAckedPn) {
                        largestAckedPn = range[1];
                    }
                }
                if (ackedBytes > 0) {
                    this.congestionControl.onPacketsAcked(ackedBytes, largestAckedPn);
                }
                // ── Notify pending PING RTT waiters ─────────────────────────
                if (!this.pendingPings.isEmpty()) {
                    long nowMs = System.currentTimeMillis();
                    for (long[] range : ackedRanges) {
                        this.pendingPings.entrySet().removeIf(entry -> {
                            long pn = entry.getKey();
                            if (pn >= range[0] && pn <= range[1]) {
                                entry.getValue().future.completed(nowMs - entry.getValue().sentTimeMs);
                                return true;
                            }
                            return false;
                        });
                    }
                }
                // Handle lost packets: retransmit
                for (byte[] lostPayload : lostPayloads) {
                    if (lostPayload != null && lostPayload.length > 0) {
                        this.congestionControl.onPacketLost(0); // signal loss event
                        // Retransmit by re-sending the lost frames
                        this.sendDataFrame(ByteBuf.wrap(lostPayload), null);
                    }
                }
                // Handle ECN counters
                if (frameType == QuicFrameType.ACK_ECN) {
                    // Skip to ECN fields (after the standard ACK body)
                    pos = skipAckFrame(payload, ackStartPos, QuicFrameType.ACK); // skip to just past ACK ranges
                    if (pos >= 0 && pos < payload.length) {
                        long[] ect0 = QuicVarInt.decode(payload, pos);
                        pos += (int) ect0[1];
                        long[] ect1 = QuicVarInt.decode(payload, pos);
                        pos += (int) ect1[1];
                        long[] ecnCe = QuicVarInt.decode(payload, pos);
                        pos += (int) ecnCe[1];
                        // Process ECN-CE congestion signal
                        this.congestionControl.onEcnCongestion(ecnCe[0], largestAckedPn);
                    }
                } else {
                    pos = skipAckFrame(payload, ackStartPos, frameType);
                }
                if (pos < 0) {
                    return;
                }
                continue;
            }

            // ── STREAM (0x08..0x0f, RFC 9000 §19.8) ────────────────────
            if (QuicFrameType.isStream(frameType)) {
                packetAckEliciting = true;
                pos = handleStreamFrame(payload, pos, frameType);
                if (pos < 0) {
                    return;
                }
                continue;
            }

            // ── DATAGRAM (0x30..0x31, RFC 9221 §4) ──────────────────────
            if (QuicFrameType.isDatagram(frameType)) {
                packetAckEliciting = true;
                pos = handleDatagramFrame(payload, pos, frameType);
                if (pos < 0) {
                    return;
                }
                continue;
            }

            // ── MAX_DATA (RFC 9000 §19.9) ───────────────────────────────
            if (frameType == QuicFrameType.MAX_DATA) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                this.negotiationMaxData = Math.max(this.negotiationMaxData, tmp[0]);
                pos += (int) tmp[1];
                continue;
            }

            // ── MAX_STREAM_DATA (RFC 9000 §19.10) ──────────────────────
            if (frameType == QuicFrameType.MAX_STREAM_DATA) {
                packetAckEliciting = true;
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] maxResult = QuicVarInt.decode(payload, pos);
                long maxStreamData = maxResult[0];
                pos += (int) maxResult[1];
                QuicStreamChannel stream = this.streamMap.get(streamId);
                if (stream != null) {
                    stream.updateMaxDataSize(maxStreamData);
                }
                continue;
            }

            // ── MAX_STREAMS (RFC 9000 §19.11) ──────────────────────────
            if (frameType == QuicFrameType.MAX_STREAMS_BIDI) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                this.peerMaxStreamsBidi = Math.max(this.peerMaxStreamsBidi, tmp[0]);
                pos += (int) tmp[1];
                continue;
            }
            if (frameType == QuicFrameType.MAX_STREAMS_UNI) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                this.peerMaxStreamsUni = Math.max(this.peerMaxStreamsUni, tmp[0]);
                pos += (int) tmp[1];
                continue;
            }

            // ── RESET_STREAM (RFC 9000 §19.4) ──────────────────────────
            if (frameType == QuicFrameType.RESET_STREAM) {
                packetAckEliciting = true;
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] errResult = QuicVarInt.decode(payload, pos);
                long errorCode = errResult[0];
                pos += (int) errResult[1];
                long[] sizeResult = QuicVarInt.decode(payload, pos);
                long finalSize = sizeResult[0];
                pos += (int) sizeResult[1];
                QuicStreamChannel stream = this.streamMap.get(streamId);
                if (stream != null) {
                    logger.info("Received RESET_STREAM for stream " + streamId + ", errorCode=" + errorCode);
                    QuicStreamResetException ex = new QuicStreamResetException(errorCode, streamId, finalSize);
                    this.context.notifyRcvChannelException(stream.getChannelId(), true, ex);
                }
                continue;
            }

            // ── STOP_SENDING (RFC 9000 §19.5) ──────────────────────────
            if (frameType == QuicFrameType.STOP_SENDING) {
                packetAckEliciting = true;
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] errResult = QuicVarInt.decode(payload, pos);
                long errorCode = errResult[0];
                pos += (int) errResult[1];
                QuicStreamChannel stream = this.streamMap.get(streamId);
                if (stream != null) {
                    logger.info("Received STOP_SENDING for stream " + streamId + ", errorCode=" + errorCode);
                    QuicStopSendingException ex = new QuicStopSendingException(errorCode, streamId);
                    this.context.notifySndChannelException(stream.getChannelId(), false, ex);
                }
                continue;
            }

            // ── CONNECTION_CLOSE (RFC 9000 §19.19) ─────────────────────
            if (frameType == QuicFrameType.CONNECTION_CLOSE || frameType == QuicFrameType.CONNECTION_CLOSE_APP) {
                long[] errResult = QuicVarInt.decode(payload, pos);
                long errorCode = errResult[0];
                pos += (int) errResult[1];
                if (frameType == QuicFrameType.CONNECTION_CLOSE) {
                    long[] ftResult = QuicVarInt.decode(payload, pos);
                    pos += (int) ftResult[1];
                }
                long[] lenResult = QuicVarInt.decode(payload, pos);
                int reasonLen = (int) lenResult[0];
                pos += (int) lenResult[1];
                String reason = reasonLen > 0 ? new String(payload, pos, reasonLen, java.nio.charset.StandardCharsets.UTF_8) : "";
                logger.info("Received CONNECTION_CLOSE: errorCode=" + errorCode + ", reason=" + reason);

                QuicConnectionCloseException ex = new QuicConnectionCloseException(errorCode, reason);
                notifyAllChannelsException(ex);

                try {
                    this.close();
                } catch (IOException e) {
                    logger.error("Failed to close connection on CONNECTION_CLOSE: " + e.getMessage());
                }
                return;
            }

            // ── CRYPTO (RFC 9000 §19.6) — handle in 1-RTT via reassembly ──
            if (frameType == QuicFrameType.CRYPTO) {
                packetAckEliciting = true;
                long[] offResult = QuicVarInt.decode(payload, pos);
                long cryptoOffset = offResult[0];
                pos += (int) offResult[1];
                long[] lenResult = QuicVarInt.decode(payload, pos);
                int cryptoLen = (int) lenResult[0];
                pos += (int) lenResult[1];
                if (cryptoLen > 0 && pos + cryptoLen <= payload.length) {
                    byte[] cryptoData = new byte[cryptoLen];
                    System.arraycopy(payload, pos, cryptoData, 0, cryptoLen);
                    // Buffer for reassembly (post-handshake messages like NewSessionTicket)
                    this.cryptoReassembler.addFragment(cryptoOffset, cryptoData, false);
                    byte[] contiguousCrypto = this.cryptoReassembler.readContiguous();
                    if (contiguousCrypto != null) {
                        // Post-handshake TLS message (e.g. NewSessionTicket, KeyUpdate)
                        handlePostHandshakeCrypto(contiguousCrypto);
                    }
                }
                pos += cryptoLen;
                continue;
            }

            // ── NEW_CONNECTION_ID (RFC 9000 §19.15) ────────────────────
            if (frameType == QuicFrameType.NEW_CONNECTION_ID) {
                packetAckEliciting = true;
                long[] seqResult = QuicVarInt.decode(payload, pos);
                long seqNum = seqResult[0];
                pos += (int) seqResult[1];
                long[] retireResult = QuicVarInt.decode(payload, pos);
                long retirePriorTo = retireResult[0];
                pos += (int) retireResult[1];
                int cidLen = payload[pos++] & 0xFF;
                byte[] newCid = new byte[cidLen];
                System.arraycopy(payload, pos, newCid, 0, cidLen);
                pos += cidLen;
                byte[] resetToken = new byte[16];
                System.arraycopy(payload, pos, resetToken, 0, 16);
                pos += 16;
                // Process via CID manager
                List<byte[]> retireFrames = this.cidManager.onNewConnectionId(seqNum, retirePriorTo, newCid, resetToken);
                for (byte[] retireFrame : retireFrames) {
                    this.sendDataFrame(ByteBuf.wrap(retireFrame), null);
                }
                continue;
            }

            // ── RETIRE_CONNECTION_ID (RFC 9000 §19.16) ─────────────────
            if (frameType == QuicFrameType.RETIRE_CONNECTION_ID) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                long seqNum = tmp[0];
                pos += (int) tmp[1];
                byte[] replacementFrame = this.cidManager.onRetireConnectionId(seqNum);
                if (replacementFrame != null) {
                    this.sendDataFrame(ByteBuf.wrap(replacementFrame), null);
                }
                continue;
            }

            // ── NEW_TOKEN (RFC 9000 §19.7) ─────────────────────────────
            if (frameType == QuicFrameType.NEW_TOKEN) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                int tokenLen = (int) tmp[0];
                pos += (int) tmp[1];
                if (tokenLen > 0 && pos + tokenLen <= payload.length) {
                    byte[] token = new byte[tokenLen];
                    System.arraycopy(payload, pos, token, 0, tokenLen);
                    // Store token for future connection attempts (client only)
                    if (this.clientMode) {
                        logger.info("Received NEW_TOKEN (len=" + tokenLen + "), stored for future connections");
                        // Token stored in quicSoConfig or session cache (implementation detail)
                    }
                }
                pos += tokenLen;
                continue;
            }

            // ── DATA_BLOCKED (RFC 9000 §19.12) ─────────────────────────
            if (frameType == QuicFrameType.DATA_BLOCKED) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                long maximumData = tmp[0];
                pos += (int) tmp[1];
                logger.info("Peer is DATA_BLOCKED at " + maximumData + " bytes (connection level)");
                // Auto-expand flow control window and send MAX_DATA
                long newMaxData = this.flowControl.shouldExpandConnectionWindow();
                if (newMaxData < 0) {
                    // Force expand since peer is blocked
                    newMaxData = Math.max(maximumData * 2, this.flowControl.getConnectionMaxData() * 2);
                    this.flowControl.updateConnectionMaxData(newMaxData);
                }
                byte[] maxDataFrame = QuicFlowControl.buildMaxDataFrame(newMaxData);
                this.sendDataFrame(ByteBuf.wrap(maxDataFrame), null);
                continue;
            }

            // ── STREAM_DATA_BLOCKED (RFC 9000 §19.13) ──────────────────
            if (frameType == QuicFrameType.STREAM_DATA_BLOCKED) {
                packetAckEliciting = true;
                long[] sidResult = QuicVarInt.decode(payload, pos);
                long streamId = sidResult[0];
                pos += (int) sidResult[1];
                long[] maxResult = QuicVarInt.decode(payload, pos);
                long maximumStreamData = maxResult[0];
                pos += (int) maxResult[1];
                logger.info("Peer is STREAM_DATA_BLOCKED on stream " + streamId + " at " + maximumStreamData + " bytes");
                QuicStreamChannel stream = this.streamMap.get(streamId);
                if (stream != null) {
                    // Auto-expand stream flow control
                    long newMax = Math.max(maximumStreamData * 2, stream.getMaxDataSize() * 2);
                    byte[] maxStreamDataFrame = QuicFlowControl.buildMaxStreamDataFrame(streamId, newMax);
                    this.sendDataFrame(ByteBuf.wrap(maxStreamDataFrame), null);
                    stream.updateMaxDataSize(newMax);
                }
                continue;
            }

            // ── STREAMS_BLOCKED (RFC 9000 §19.14) ──────────────────────
            if (frameType == QuicFrameType.STREAMS_BLOCKED_BIDI || frameType == QuicFrameType.STREAMS_BLOCKED_UNI) {
                packetAckEliciting = true;
                long[] tmp = QuicVarInt.decode(payload, pos);
                long maximumStreams = tmp[0];
                pos += (int) tmp[1];
                boolean bidi = (frameType == QuicFrameType.STREAMS_BLOCKED_BIDI);
                logger.info("Peer is STREAMS_BLOCKED (" + (bidi ? "bidi" : "uni") + ") at " + maximumStreams);
                // Notify the connection-level channel so the application can decide to increase limits
                if (this.quicChannel != null) {
                    // Auto-expand: increase by 10 more streams
                    long currentMax = bidi ? this.quicSoConfig.getTpInitialMaxStreamsBidi() : this.quicSoConfig.getTpInitialMaxStreamsUni();
                    long newMax = Math.max(maximumStreams + 10, currentMax + 10);
                    int maxFrameType = bidi ? QuicFrameType.MAX_STREAMS_BIDI : QuicFrameType.MAX_STREAMS_UNI;
                    byte[] typeBytes = QuicVarInt.encode(maxFrameType);
                    byte[] valBytes = QuicVarInt.encode(newMax);
                    byte[] frame = new byte[typeBytes.length + valBytes.length];
                    System.arraycopy(typeBytes, 0, frame, 0, typeBytes.length);
                    System.arraycopy(valBytes, 0, frame, typeBytes.length, valBytes.length);
                    this.sendDataFrame(ByteBuf.wrap(frame), null);
                }
                continue;
            }

            // ── PATH_CHALLENGE (RFC 9000 §19.17) ───────────────────────
            if (frameType == QuicFrameType.PATH_CHALLENGE) {
                packetAckEliciting = true;
                byte[] challengeData = new byte[8];
                System.arraycopy(payload, pos, challengeData, 0, 8);
                pos += 8;
                // Respond with PATH_RESPONSE
                byte[] responseFrame = this.pathValidator.onPathChallenge(challengeData);
                if (responseFrame != null) {
                    this.sendDataFrame(ByteBuf.wrap(responseFrame), null);
                }
                continue;
            }

            // ── PATH_RESPONSE (RFC 9000 §19.18) ────────────────────────
            if (frameType == QuicFrameType.PATH_RESPONSE) {
                packetAckEliciting = true;
                byte[] responseData = new byte[8];
                System.arraycopy(payload, pos, responseData, 0, 8);
                pos += 8;
                this.pathValidator.onPathResponse(responseData);
                continue;
            }

            // ── Unknown frame type — cannot determine length, connection error ──
            String msg = "Unknown QUIC frame type 0x" + Integer.toHexString(frameType) + " at offset " + (pos - (int) typeResult[1]);
            logger.error(msg);
            QuicException frameEx = new QuicException(QuicErrorCode.FRAME_ENCODING_ERROR, msg);
            notifyAllChannelsException(frameEx);
            this.closeWithError(QuicErrorCode.FRAME_ENCODING_ERROR, msg, null);
            return;
        }

        // ── Record received packet for ACK generation ───────────────
        long pn = this.handshake.getLargestAppPn();
        this.ackTracker.onPacketReceived(pn, packetAckEliciting);

        // ── Check PTO timer for probe ───────────────────────────────
        if (this.sentPacketTracker.isPtoExpired()) {
            // Send a PING as PTO probe (RFC 9002 §6.2.4)
            byte[] pingFrame = QuicVarInt.encode(QuicFrameType.PING);
            this.sendDataFrame(ByteBuf.wrap(pingFrame), null);
            this.sentPacketTracker.onPtoSent();
        }

        // ── Auto-send ACK if threshold reached ──────────────────────
        if (this.ackTracker.shouldSendAck()) {
            byte[] ackFrame = this.ackTracker.generateAckFrame();
            if (ackFrame != null) {
                this.sendDataFrame(ByteBuf.wrap(ackFrame), null);
            }
        }

        // ── Check flow control window expansion ─────────────────────
        long newMaxData = this.flowControl.shouldExpandConnectionWindow();
        if (newMaxData > 0) {
            byte[] maxDataFrame = QuicFlowControl.buildMaxDataFrame(newMaxData);
            this.sendDataFrame(ByteBuf.wrap(maxDataFrame), null);
        }

        // ── Check path validation timeouts ──────────────────────────
        this.pathValidator.checkTimeouts();
    }

    /**
     * Handles post-handshake CRYPTO data received in 1-RTT packets.
     * This includes NewSessionTicket (0x04) and KeyUpdate (0x18) TLS messages.
     */
    private void handlePostHandshakeCrypto(byte[] data) {
        if (data == null || data.length < 4) {
            return;
        }
        int msgType = data[0] & 0xFF;
        if (msgType == 0x18) {
            // KeyUpdate (TLS 1.3, RFC 8446 §4.6.3)
            handleKeyUpdate(data);
        } else if (msgType == 0x04) {
            // NewSessionTicket (TLS 1.3, RFC 8446 §4.6.1)
            logger.info("Received post-handshake NewSessionTicket (len=" + data.length + ")");
            // Store session ticket for 0-RTT resumption (future enhancement)
        } else {
            logger.info("Received post-handshake CRYPTO message type=0x" + Integer.toHexString(msgType));
        }
    }

    /**
     * Handles a TLS KeyUpdate message (RFC 8446 §4.6.3).
     * <p>
     * KeyUpdate structure: (1 byte type=0x18) + (3 bytes length) + (1 byte request_update)
     * <p>
     * request_update values:
     * <ul>
     *     <li>0 = update_not_requested</li>
     *     <li>1 = update_requested</li>
     * </ul>
     * This rotates the peer's read keys and optionally our write keys.
     */
    private void handleKeyUpdate(byte[] data) {
        // KeyUpdate: type(1) + length(3) + request_update(1) = 5 bytes total
        if (data.length < 5) {
            logger.error("KeyUpdate message too short: " + data.length);
            return;
        }
        // Parse length from 3-byte big-endian
        int msgLen = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (msgLen < 1) {
            logger.error("KeyUpdate message body too short: " + msgLen);
            return;
        }
        int requestUpdate = data[4] & 0xFF;
        logger.info("Received KeyUpdate: request_update=" + requestUpdate);

        try {
            // Rotate peer's read keys  (their write keys updated → our read keys must update)
            this.handshake.rotateReadKeys();
            logger.info("Read keys rotated after KeyUpdate");

            if (requestUpdate == 1) {
                // Peer requested us to update too — rotate our write keys and send KeyUpdate response
                this.handshake.rotateWriteKeys();
                logger.info("Write keys rotated (peer requested update)");
                // Send our KeyUpdate with update_not_requested
                byte[] kuResponse = new byte[] { 0x18,                         // HandshakeType: key_update
                        0x00, 0x00, 0x01,             // Length: 1
                        0x00                          // request_update: update_not_requested
                };
                byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, kuResponse);
                this.sendDataFrame(ByteBuf.wrap(cryptoFrame), null);
            }
        } catch (Exception e) {
            logger.error("Failed to perform key update: " + e.getMessage());
            QuicException ex = new QuicException(QuicErrorCode.INTERNAL_ERROR, "KeyUpdate failed: " + e.getMessage());
            notifyAllChannelsException(ex);
            this.closeWithError(QuicErrorCode.INTERNAL_ERROR, "KeyUpdate failed", null);
        }
    }

    /**
     * Parses a STREAM frame, auto-creates the stream if it's peer-initiated and unknown,
     * and delivers data to the stream's pipeline. Returns the new position, or {@code -1} on error.
     */
    private int handleStreamFrame(byte[] data, int pos, int frameType) {
        // Parse Stream ID
        long[] sidResult = QuicVarInt.decode(data, pos);
        long streamId = sidResult[0];
        pos += (int) sidResult[1];

        // Parse Offset (if OFF bit set)
        long offset = 0;
        if (QuicFrameType.streamOff(frameType)) {
            long[] offResult = QuicVarInt.decode(data, pos);
            offset = offResult[0];
            pos += (int) offResult[1];
        }

        // Parse Length (if LEN bit set) or consume remaining
        int dataLength;
        if (QuicFrameType.streamLen(frameType)) {
            long[] lenResult = QuicVarInt.decode(data, pos);
            dataLength = (int) lenResult[0];
            pos += (int) lenResult[1];
        } else {
            dataLength = data.length - pos;
        }

        boolean fin = QuicFrameType.streamFin(frameType);

        // ── Find or auto-create stream ──────────────────────────────────
        QuicStreamChannel stream = this.streamMap.get(streamId);
        if (stream == null) {
            boolean clientInitiated = (streamId & 0x01) == 0;
            boolean locallyInitiated = (this.clientMode == clientInitiated);
            if (!locallyInitiated) {
                // Auto-create peer-initiated stream
                Future<QuicStreamChannel> f = newStreamChannel(streamId);
                stream = f.getResult();
                if (stream == null) {
                    logger.error("Failed to auto-create stream " + streamId + ": " + (f.getCause() != null ? f.getCause().getMessage() : "unknown"));
                    return pos + dataLength; // skip data
                }
            } else {
                // Locally-initiated stream not in map — RFC 9000 §19.8: an endpoint MUST
                // terminate the connection with STREAM_STATE_ERROR if it receives a STREAM
                // frame for a locally initiated stream that has not yet been created.
                String msg = "Received STREAM frame for unknown local stream " + streamId;
                logger.error(msg);
                QuicException stateEx = new QuicException(QuicErrorCode.STREAM_STATE_ERROR, msg);
                notifyAllChannelsException(stateEx);
                this.closeWithError(QuicErrorCode.STREAM_STATE_ERROR, msg, null);
                return -1; // stop processing
            }
        }

        // ── Uni-stream send-rights check ──────────────────────────────────────────
        // RFC 9000 §3.4: only the stream initiator may send data on a uni stream.
        // Receiving a STREAM frame for a locally-initiated uni stream from the peer
        // is a STREAM_STATE_ERROR (RFC 9000 §19.8).
        if ((streamId & 0x02) != 0) { // uni stream: bit 1 is set
            boolean clientInitiated = (streamId & 0x01) == 0;
            boolean locallyInitiated = (this.clientMode == clientInitiated);
            if (locallyInitiated) {
                String msg = "Received STREAM frame on locally-initiated uni stream " + streamId;
                logger.error(msg);
                QuicException stateEx = new QuicException(QuicErrorCode.STREAM_STATE_ERROR, msg);
                notifyAllChannelsException(stateEx);
                this.closeWithError(QuicErrorCode.STREAM_STATE_ERROR, msg, null);
                return -1;
            }
        }

        // ── Flow control validation ────────────────────────────────────
        if (dataLength > 0) {
            // Connection-level flow control
            if (!this.flowControl.onConnectionDataReceived(dataLength)) {
                String fcMsg = "Connection-level flow control exceeded";
                logger.error(fcMsg);
                QuicException fcEx = new QuicException(QuicErrorCode.FLOW_CONTROL_ERROR, fcMsg);
                notifyAllChannelsException(fcEx);
                this.closeWithError(QuicErrorCode.FLOW_CONTROL_ERROR, fcMsg, null);
                return -1;
            }
            // Stream-level flow control
            if (!this.flowControl.validateStreamData(offset, dataLength, stream.getMaxDataSize())) {
                String fcMsg = "Stream-level flow control exceeded on stream " + streamId;
                logger.error(fcMsg);
                QuicException fcEx = new QuicException(QuicErrorCode.FLOW_CONTROL_ERROR, fcMsg);
                notifyAllChannelsException(fcEx);
                this.closeWithError(QuicErrorCode.FLOW_CONTROL_ERROR, fcMsg, null);
                return -1;
            }
        }

        // ── Reassembly and delivery (always via reassembler for correct multi-message support) ───
        if (dataLength > 0 || fin) {
            stream.touchActivity();
            // Always use reassembler so that subsequent messages (offset > 0) are correctly
            // sequenced with the first message (offset == 0). Direct delivery for offset==0
            // would bypass the reassembler and cause subsequent messages to be dropped because
            // the reassembler would still expect offset 0 when processing msg2, msg3, etc.
            QuicStreamReassembler reassembler = this.streamReassemblers.get(streamId);
            if (reassembler == null) {
                reassembler = new QuicStreamReassembler();
                this.streamReassemblers.put(streamId, reassembler);
            }
            if (dataLength > 0) {
                byte[] fragment = new byte[dataLength];
                System.arraycopy(data, pos, fragment, 0, dataLength);
                reassembler.addFragment(offset, fragment, fin);
            } else if (fin) {
                reassembler.addFragment(offset, new byte[0], true);
            }
            // Deliver all contiguous data available
            byte[] contiguous = reassembler.readContiguous();
            if (contiguous != null) {
                ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(contiguous.length);
                byteBuf.writeBytes(contiguous);
                byteBuf.markWriter();
                this.context.notifyRcvChannelData(stream.getChannelId(), byteBuf);
            }
            // Check if stream is fully received (FIN delivered)
            if (reassembler.isComplete()) {
                this.context.notifyRcvChannelData(stream.getChannelId(), ByteBuf.EMPTY);
                this.streamReassemblers.remove(streamId);
            }
        }
        pos += dataLength;

        // ── Auto-expand stream flow control window if needed ────────────
        long newStreamMax = this.flowControl.shouldExpandStreamWindow(offset + dataLength, stream.getMaxDataSize());
        if (newStreamMax > 0) {
            byte[] maxStreamDataFrame = QuicFlowControl.buildMaxStreamDataFrame(streamId, newStreamMax);
            this.sendDataFrame(ByteBuf.wrap(maxStreamDataFrame), null);
            stream.updateMaxDataSize(newStreamMax);
        }

        return pos;
    }

    /**
     * Parses a DATAGRAM frame, auto-creates the datagram channel if needed,
     * and delivers data to its pipeline. Returns the new position, or {@code -1} on error.
     * <p>
     * Per RFC 9221 §5: if the local endpoint did not advertise {@code max_datagram_frame_size}
     * in its transport parameters, receiving a DATAGRAM frame is a connection error of type
     * {@code PROTOCOL_VIOLATION}. If support was advertised but is administratively disabled,
     * the data is silently discarded.
     */
    private int handleDatagramFrame(byte[] data, int pos, int frameType) {
        int dataLength;
        if (QuicFrameType.datagramHasLen(frameType)) {
            long[] lenResult = QuicVarInt.decode(data, pos);
            dataLength = (int) lenResult[0];
            pos += (int) lenResult[1];
        } else {
            dataLength = data.length - pos;
        }

        // ── RFC 9221 §5: DATAGRAM not negotiated → PROTOCOL_VIOLATION ──
        if (this.negotiationDatagramMaxData == 0) {
            String pvMsg = "DATAGRAM frame received without max_datagram_frame_size negotiation";
            logger.error("Received DATAGRAM frame but max_datagram_frame_size was not negotiated");
            QuicException pvEx = new QuicException(QuicErrorCode.PROTOCOL_VIOLATION, pvMsg);
            notifyAllChannelsException(pvEx);
            this.closeWithError(QuicErrorCode.PROTOCOL_VIOLATION, pvMsg, null);
            return -1; // stop processing
        }

        // ── Administratively disabled — silently discard ────────────────
        if (this.quicSoConfig.isDisableDatagram()) {
            logger.warn("Received DATAGRAM frame but datagram is administratively disabled, discarding");
            return pos + dataLength;
        }

        // ── Find or auto-create datagram channel ────────────────────────
        QuicDatagramChannel dgCh = this.datagramChannel;
        if (dgCh == null) {
            Future<QuicDatagramChannel> f = getOrCreateDatagramChannel();
            dgCh = f.getResult();
            if (dgCh == null) {
                logger.warn("Cannot deliver DATAGRAM: datagram channel creation failed");
                return pos + dataLength; // skip data
            }
        }

        // ── Deliver data to datagram channel's pipeline ─────────────────
        if (dataLength > 0) {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(dataLength);
            byteBuf.writeBytes(data, pos, dataLength);
            byteBuf.markWriter();
            this.context.notifyRcvChannelData(dgCh.getChannelId(), byteBuf);
        }
        pos += dataLength;
        return pos;
    }

    /**
     * Propagates a QUIC exception to all open sub-channel pipelines (streams + datagram)
     * as a <em>receive</em> error, so that application handlers are notified of the error.
     * The {@code doClose} parameter of {@code notifyRcvChannelException} is {@code false}
     * because channel cleanup is handled separately by {@link #closeAllStreams()}.
     */
    void notifyAllChannelsException(SoException ex) {
        // Notify connection-level channel
        if (this.quicChannel != null) {
            this.context.notifyRcvChannelException(this.quicChannel.getChannelId(), false, ex);
        }
        // Notify all stream channels
        for (QuicStreamChannel stream : this.streamMap.values()) {
            this.context.notifyRcvChannelException(stream.getChannelId(), false, ex);
        }
        // Notify datagram channel
        QuicDatagramChannel dg = this.datagramChannel;
        if (dg != null) {
            this.context.notifyRcvChannelException(dg.getChannelId(), false, ex);
        }
    }

    /** Checks and enforces idle timeouts for both the connection and individual streams. */
    void checkIdleTimeouts() {
        if (this.closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();

        // ── Connection-level idle timeout (RFC 9000 §10.1) ──────────
        long connIdleTimeout = this.quicSoConfig.getTpMaxIdleTimeout();
        if (connIdleTimeout > 0) {
            long elapsed = now - this.lastActivityTime;
            if (elapsed >= connIdleTimeout) {
                logger.info("Connection idle timeout after " + elapsed + "ms (limit=" + connIdleTimeout + "ms)");
                QuicIdleTimeoutException ex = new QuicIdleTimeoutException(//
                        "Connection idle timeout: " + elapsed + "ms >= " + connIdleTimeout + "ms", true);
                notifyAllChannelsException(ex);
                // RFC 9000 §10.1: idle timeout is not signaled with CONNECTION_CLOSE; just close silently.
                try {
                    this.close();
                } catch (IOException e) {
                    logger.error("Failed to close connection on idle timeout: " + e.getMessage());
                }
                return;
            }
        }

        // ── Stream-level idle timeout ───────────────────────────────
        long streamIdleTimeout = this.quicSoConfig.getStreamIdleTimeoutMs();
        if (streamIdleTimeout <= 0) {
            return;
        }
        for (QuicStreamChannel stream : this.streamMap.values()) {
            long streamElapsed = now - stream.getLastActivityTime();
            if (streamElapsed >= streamIdleTimeout) {
                logger.info("Stream " + stream.getStreamId() + " idle timeout after " + streamElapsed + "ms (limit=" + streamIdleTimeout + "ms)");
                QuicIdleTimeoutException ex = new QuicIdleTimeoutException(//
                        "Stream " + stream.getStreamId() + " idle timeout: " + streamElapsed + "ms >= " + streamIdleTimeout + "ms", false);
                this.context.notifyRcvChannelException(stream.getChannelId(), true, ex);
            }
        }
    }

    /** Initiates an active connection migration (RFC 9000 §9). */
    Future<Long> migrate() throws IOException {
        // 1. Issue a new local CID so the peer can address us with a fresh ID
        byte[] newCidFrame = this.cidManager.issueNewConnectionId();
        if (newCidFrame != null) {
            sendDataFrame(ByteBuf.wrap(newCidFrame), null);
        }
        // 2. Rotate to the next available remote CID
        this.cidManager.rotateRemoteCid();
        // 3. Reset congestion controller for new path
        this.congestionControl.reset();
        // 4. Initiate PATH_CHALLENGE and wire it to a future
        BasicFuture<Long> validationFuture = new BasicFuture<>();
        byte[] pathChallengeFrame = this.pathValidator.initiateChallenge(validationFuture);
        sendDataFrame(ByteBuf.wrap(pathChallengeFrame), null);
        return validationFuture;
    }

    /** Lightweight holder for a pending PING: the future to complete and the send timestamp. */
    private static final class PendingPing {
        final BasicFuture<Long> future;
        final long              sentTimeMs;

        PendingPing(BasicFuture<Long> future, long sentTimeMs) {
            this.future = future;
            this.sentTimeMs = sentTimeMs;
        }
    }

    private static class CloseQuicChannelTask extends DefaultSoTask {
        private final NetChannel channel;

        public CloseQuicChannelTask(NetChannel channel) {
            this.channel = channel;
        }

        @Override
        protected void doWork(int retryCnt) {
            try {
                this.channel.closeNow();
            } catch (Exception e) {
                logger.error("Failed to close datagram channel: " + e.getMessage());
            }
            finishTask();
        }
    }
}