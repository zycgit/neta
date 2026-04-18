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
import java.nio.channels.DatagramChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.Futures;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * Core implementation of a QUIC connection built on top of UDP.
 * <p><b>The internal subsystem layout is as follows:</b>
 * <pre>
 *   inbound UDP packet
 *        |
 *        +--> packet decrypt / parse
 *        +--> ACK tracker
 *        +--> sent-packet tracker + congestion control
 *        +--> flow control
 *        +--> stream reassembly / stream dispatch
 *        +--> datagram dispatch
 *   outbound frame
 *        |
 *        +--> ACK coalescing
 *        +--> short-header packet build
 *        +--> sent-packet tracking
 *        +--> UDP send
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicChannelAsync implements AsyncChannel {
    private static final Logger logger = Logger.getLogger(QuicChannelAsync.class);
    private final long          channelId;
    private final SocketAddress localAddress;
    private final boolean       clientMode;
    private final QuicSoConfig  quicSoConfig;
    //
    private final DatagramChannel           ownerUdp;
    private final NetListen                 forListen;
    private final ProtoInitializer          initializer;
    private final SoContextService          context;
    private final QuicAsyncChannelHandshake handshake;
    //
    private final AtomicBoolean closed;
    //
    private final Set<Long>                    streamIds;
    private final Map<Long, QuicStreamChannel> streamMap;
    private final Map<Long, QuicStreamState>   streamStates;
    //
    // ── ACK, Loss Detection, Congestion Control, Flow Control
    private final QuicAckTracker        ackTracker;
    private final QuicSentPacketTracker sentPacketTracker;
    private final QuicCongestionControl congestionControl;
    private final QuicFlowControl       flowControl;
    //
    // ── Stream Reassembly
    private final Map<Long, QuicStreamReassembler> streamReassemblers;
    //
    // ── CRYPTO frame reassembly for post-handshake messages (1-RTT) ──
    private final QuicStreamReassembler cryptoReassembler;
    //
    // ── Connection ID Management
    private final QuicConnectionIdManager cidManager;
    //
    // ── Path Validation
    private final QuicPathValidator      pathValidator;
    private final Map<Long, PendingPing> pendingPings;
    private final AtomicBoolean          messageWriting;
    private final long                   connectionDatagramMaxData; // Last time (in milliseconds) any packet was sent or received on this connection.
    private volatile long                lastActivityTime;
    private volatile long                connectionMaxData;
    // stream for bidi
    private volatile long peerMaxStreamsBidi;
    private volatile long nextRemoteBidiStreamId;
    private volatile long peerStreamMaxDataBidiLocal;
    private volatile long peerStreamMaxDataBidiRemote;
    // stream for uni
    private volatile long                peerMaxStreamsUni;
    private volatile long                nextRemoteUniStreamId;
    private volatile long                peerStreamMaxDataUni;
    private QuicChannel                  quicChannel;
    private volatile QuicDatagramChannel datagramChannel;
    private volatile SocketAddress       remoteAddress;

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
        this.connectionMaxData = Math.min(handshakeData.getPeerMaxData(), soConfig.getTpInitialFrameMaxData());
        this.connectionDatagramMaxData = Math.min(handshakeData.getDatagramMaxDataSize(), soConfig.getTpInitialDatagramFrameMaxData());
        this.peerMaxStreamsBidi = handshakeData.getPeerMaxStreamsBidi();
        this.peerMaxStreamsUni = handshakeData.getPeerMaxStreamsUni();
        this.peerStreamMaxDataBidiLocal = handshakeData.getPeerStreamMaxDataBidiLocal();
        this.peerStreamMaxDataBidiRemote = handshakeData.getPeerStreamMaxDataBidiRemote();
        this.peerStreamMaxDataUni = handshakeData.getPeerStreamMaxDataUni();
        this.nextRemoteBidiStreamId = this.clientMode ? 1 : 0; // Server-initiated streams use 1,5,9... (bidi) and 3,7,11... (uni).
        this.nextRemoteUniStreamId = this.clientMode ? 3 : 2;  // Client-initiated streams use 0,4,8... (bidi) and 2,6,10... (uni).
        //
        this.streamIds = ConcurrentHashMap.newKeySet();
        this.streamMap = new ConcurrentHashMap<>();
        this.streamStates = new ConcurrentHashMap<>();
        //
        // Initialize ACK tracking, loss detection, congestion control, and flow control components.
        this.ackTracker = new QuicAckTracker();
        this.sentPacketTracker = new QuicSentPacketTracker();
        this.congestionControl = new QuicCongestionControl();
        this.flowControl = new QuicFlowControl(soConfig.getTpInitialFrameMaxData());
        this.streamReassemblers = new ConcurrentHashMap<>();
        this.cryptoReassembler = new QuicStreamReassembler();
        this.cidManager = new QuicConnectionIdManager(handshake.getLocalCid(), handshake.getRemoteCid(), soConfig.getConnectionIdLength());
        this.pathValidator = new QuicPathValidator();
        this.pendingPings = new ConcurrentHashMap<>();
        this.messageWriting = new AtomicBoolean(false);
    }

    /**
     * Skips an ACK or ACK_ECN frame body and returns the new read position; returns {@code -1}
     * if parsing fails.
     */
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

    /**
     * Determines whether the payload contains ACK-eliciting frames, meaning frames other than
     * ACK and PADDING.
     */
    private static boolean containsAckElicitingFrames(byte[] payload) {
        int pos = 0;
        while (pos < payload.length) {
            long[] typeResult = QuicVarInt.decode(payload, pos);
            int ft = (int) typeResult[0];
            return ft != QuicFrameType.ACK && ft != QuicFrameType.ACK_ECN && ft != QuicFrameType.PADDING;
            // No need to continue skipping frame by frame here because the current goal is only
            // to distinguish ACK-only packets from others. If the first frame is not ACK/PADDING,
            // the packet is ACK-eliciting.
        }
        return false;
    }

    /**
     * Sets the back-reference to the connection-level QuicChannel, called by the
     * {@link QuicChannel} constructor.
     */
    void setQuicChannel(QuicChannel quicChannel) {
        this.quicChannel = quicChannel;
    }

    /**
     * Returns the handshake handler associated with the current connection.
     */
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

    /**
     * Updates the remote address after connection migration is detected.
     */
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

    public long getConnectionMaxData() {
        return this.connectionMaxData;
    }

    public long getPeerDatagramMaxData() {
        return this.connectionDatagramMaxData;
    }

    public long getPeerMaxStreamsBidi() {
        return this.peerMaxStreamsBidi;
    }

    public long getPeerMaxStreamsUni() {
        return this.peerMaxStreamsUni;
    }

    /**
     * Returns the most recent activity time of the current connection in epoch milliseconds.
     */
    long getLastActivityTime() {
        return this.lastActivityTime;
    }

    /**
     * Returns the ACK tracker used by the current connection.
     */
    QuicAckTracker getAckTracker() {
        return this.ackTracker;
    }

    /**
     * Returns the sent-packet tracker used for loss detection.
     */
    QuicSentPacketTracker getSentPacketTracker() {
        return this.sentPacketTracker;
    }

    /**
     * Returns the congestion controller of the current connection.
     */
    QuicCongestionControl getCongestionControl() {
        return this.congestionControl;
    }

    /**
     * Returns the flow-control tracker of the current connection.
     */
    QuicFlowControl getFlowControl() {
        return this.flowControl;
    }

    /**
     * Returns the Connection ID manager.
     */
    QuicConnectionIdManager getCidManager() {
        return this.cidManager;
    }

    /**
     * Returns the path validator.
     */
    QuicPathValidator getPathValidator() {
        return this.pathValidator;
    }

    /**
     * Returns whether the current connection has been closed.
     */
    boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Checks whether the trailing 16 bytes of a received short-header datagram match any peer
     * Stateless Reset Token known to this connection (RFC 9000 §10.3.1). Long-header packets and
     * buffers shorter than 21 bytes are rejected.
     */
    boolean matchesStatelessResetToken(byte[] datagram) {
        if (datagram == null || datagram.length < 21) {
            return false;
        }
        if ((datagram[0] & 0x80) != 0) {
            return false; // long header
        }
        byte[] token = new byte[16];
        System.arraycopy(datagram, datagram.length - 16, token, 0, 16);
        return this.cidManager.isStatelessReset(token);
    }

    /**
     * Enters the draining period (RFC 9000 §10.2.2 / §10.3.1) without transmitting any additional
     * frames. Used when a received packet has been identified as a Stateless Reset.
     */
    void enterDrainingSilently(String reason) {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        logger.warn("[QUIC] entering draining period: " + reason);
        QuicConnectionCloseException ex = new QuicConnectionCloseException(QuicErrorCode.NO_ERROR, reason);
        notifyAllChannelsException(ex);
        closeAllStreams();
    }

    /**
     * Returns the number of active streams on the current connection.
     */
    int getActiveStreamCount() {
        return this.streamMap.size();
    }

    /**
     * Returns the number of PING requests currently still in flight.
     */
    int getPendingPingCount() {
        return this.pendingPings.size();
    }

    //

    /**
     * Updates the connection-level MAX_DATA limit to the given value if it is larger than the
     * current one.
     */
    public void updateGlobalMaxDataSize(long globalMaxDataSize) {
        this.connectionMaxData = Math.max(this.connectionMaxData, globalMaxDataSize);
    }

    /**
     * Applies transport parameters negotiated from the peer's handshake data.
     */
    public void updateInitConfigData(QuicInitConfigData initConfigData) {
        if (this.closed.get() || initConfigData == null) {
            return;
        }

        if (initConfigData.getPeerMaxData() > 0) {
            this.connectionMaxData = Math.max(this.connectionMaxData, initConfigData.getPeerMaxData());
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
        // datagramMaxDataSize is negotiated during the handshake, see RFC 9221 §3, and is not updated dynamically here.
    }

    public Set<Long> getStreamIds() {
        return Collections.unmodifiableSet(this.streamIds);
    }

    boolean isStreamChannelMode() {
        return this.quicSoConfig.isStreamChannelMode();
    }

    boolean isMessageMuxMode() {
        return this.quicSoConfig.isMessageMuxMode();
    }

    public QuicStreamChannel findStream(long streamId) {
        return this.streamMap.getOrDefault(streamId, null);
    }

    PreparedQuicMessage prepareMessageWrite(QuicMessage message) throws Throwable {
        if (message == null) {
            throw new IllegalArgumentException("message is null.");
        }
        if (!this.isMessageMuxMode()) {
            throw new IllegalStateException("QUIC message mux mode is disabled.");
        }

        QuicStreamState state = this.ensureOutboundMessageStreamState(message.streamId());
        ByteBuf byteBuf = message.content();
        int length = byteBuf != null ? byteBuf.readableBytes() : 0;
        byte[] data = length > 0 ? byteBuf.asByteArray() : new byte[0];
        long offset = state.getSendOffset();
        byte[] frame = buildStreamData(message.streamId(), offset, data, message.isFin());
        return new PreparedQuicMessage(state, frame, length);
    }

    private QuicStreamState ensureOutboundMessageStreamState(long streamId) throws Throwable {
        synchronized (this.streamMap) {
            QuicStreamState state = this.streamStates.get(streamId);
            if (state != null) {
                return state;
            }

            boolean clientInitiated = (streamId & 0x01) == 0;
            boolean locallyInitiated = (this.clientMode == clientInitiated);
            if (!locallyInitiated) {
                throw new IllegalStateException("Peer-initiated stream " + streamId + " has not been opened yet.");
            }
            return this.registerStreamState(streamId, false);
        }
    }

    private QuicStreamState ensureInboundStreamState(long streamId) throws Throwable {
        synchronized (this.streamMap) {
            QuicStreamState state = this.streamStates.get(streamId);
            if (state != null) {
                return state;
            }
            return this.registerStreamState(streamId, false);
        }
    }

    private QuicStreamState registerStreamState(long streamId, boolean failIfExists) throws Throwable {
        if (this.closed.get()) {
            throw new SoCloseException("Channel is closed");
        }

        QuicStreamState existing = this.streamStates.get(streamId);
        if (existing != null) {
            if (failIfExists) {
                throw new IllegalStateException("Stream " + streamId + " already exists");
            }
            return existing;
        }

        boolean clientInitiated = (streamId & 0x01) == 0;
        boolean locallyInitiated = (this.clientMode == clientInitiated);
        boolean bidi = (streamId & 0x02) == 0;
        if (!locallyInitiated) {
            long ourLimit = bidi ? this.quicSoConfig.getTpInitialMaxStreamsBidi() : this.quicSoConfig.getTpInitialMaxStreamsUni();
            long streamIndex = streamId / 4;
            if (streamIndex >= ourLimit) {
                String streamLimitViolation = "STREAM_LIMIT_ERROR: remote stream index " + streamIndex + " exceeds local max_streams=" + ourLimit + " (streamId=" + streamId + ')';
                this.closeWithError(QuicErrorCode.STREAM_LIMIT_ERROR, streamLimitViolation, null);
                throw new IllegalStateException(streamLimitViolation);
            }
            if (bidi) {
                this.nextRemoteBidiStreamId = Math.max(this.nextRemoteBidiStreamId, streamId + 4);
            } else {
                this.nextRemoteUniStreamId = Math.max(this.nextRemoteUniStreamId, streamId + 4);
            }
        }

        long streamMaxData;
        if (bidi) {
            streamMaxData = locallyInitiated ? this.peerStreamMaxDataBidiRemote : this.quicSoConfig.getTpInitialMaxStreamDataBidiRemote();
        } else {
            streamMaxData = locallyInitiated ? this.peerStreamMaxDataUni : this.quicSoConfig.getTpInitialMaxStreamDataUni();
        }

        QuicStreamState streamState = new QuicStreamState(streamId, streamMaxData);
        this.streamStates.put(streamId, streamState);
        this.streamIds.add(streamId);
        return streamState;
    }

    void onMessageWriteTaskFinished() {
        this.messageWriting.set(false);
    }

    public QuicDatagramChannel onlyGetDatagramChannel() {
        return this.datagramChannel;
    }

    /**
     * Creates a new QUIC stream channel with the given stream ID.
     */
    public Future<QuicStreamChannel> newStreamChannel(long streamId) {
        if (this.closed.get()) {
            return Futures.buildFailed(new SoCloseException("Channel is closed"));
        }
        if (this.isMessageMuxMode()) {
            return Futures.buildFailed(new IllegalStateException("Stream channels are disabled in QUIC message mux mode."));
        }

        BasicFuture<QuicStreamChannel> future = new BasicFuture<>();
        String streamLimitViolation = null;

        synchronized (this.streamMap) {
            try {
                // 1. Basic validation.
                if (streamId < 0) {
                    future.failed(new IllegalArgumentException("Stream ID must be non-negative: " + streamId));
                    return future;
                }
                if (this.quicChannel == null) {
                    future.failed(new IllegalStateException("QuicChannel not yet created; handshake may not be complete"));
                    return future;
                }
                if (this.streamMap.containsKey(streamId) || this.streamStates.containsKey(streamId)) {
                    future.failed(new IllegalStateException("Stream " + streamId + " already exists"));
                    return future;
                }
                future.completed(doCreateStream(streamId));
            } catch (Throwable e) {
                future.failed(e);
            }
        }

        // Network I/O such as sending CONNECTION_CLOSE must not run while holding the streamMap lock.
        if (streamLimitViolation != null) {
            this.closeWithError(QuicErrorCode.STREAM_LIMIT_ERROR, streamLimitViolation, null);
        }
        return future;
    }

    //

    /**
     * Internal helper that creates a single stream channel, initializes its pipeline, and
     * registers it in the tracking maps.
     */
    private QuicStreamChannel doCreateStream(long streamId) throws Throwable {
        QuicStreamState streamState = this.registerStreamState(streamId, true);

        boolean clientInitiated = (streamId & 0x01) == 0;
        boolean localInitiated = (this.clientMode == clientInitiated);
        boolean bidi = (streamId & 0x02) == 0;

        // Create the async channel and the public stream channel.
        long channelId = this.context.nextID();
        NetMonitor monitor = new NetMonitor();
        QuicStreamChannelAsync streamAsync = new QuicStreamChannelAsync(//
                channelId, streamId, this.quicChannel, this.context);
        QuicStreamChannel streamCh = new QuicStreamChannel(                    //
                channelId, streamId, monitor, this.forListen, this.initializer,//
                streamAsync, this.context, this.quicChannel, streamState.getMaxDataSize());

        // ── Initialize pipeline (ProtoInitializer → onInit → onActive) ──
        this.context.initChannel(streamCh, true);

        // ── Register in tracking maps ────────────────────────────────────
        this.streamMap.put(streamId, streamCh);

        if (this.context.getConfig().isPrintLog()) {
            logger.info("[QUIC] stream=" + streamId + (bidi ? " bidi" : " uni") + (localInitiated ? " local" : " remote") + " maxData=" + streamState.getMaxDataSize() + " channelMode");
        }
        return streamCh;
    }

    public Future<QuicDatagramChannel> getOrCreateDatagramChannel() {
        if (this.closed.get()) {
            return Futures.buildFailed(new SoCloseException("Channel is closed"));
        }

        // channel already exists (volatile read, no lock needed)
        if (this.datagramChannel != null) {
            return Futures.buildCompleted(this.datagramChannel);
        }

        BasicFuture<QuicDatagramChannel> future = new BasicFuture<>();
        try {
            // ── Guard checks ──────────────────────────────────────────────
            if (this.quicSoConfig.isDisableDatagram()) {
                future.failed(new IllegalStateException("DATAGRAM channel is disabled by local configuration"));
                return future;
            }
            // RFC 9221 §3 requires both peers to advertise max_datagram_frame_size > 0 before DATAGRAM can be enabled.
            if (this.connectionDatagramMaxData == 0) {
                future.failed(new IllegalStateException("DATAGRAM frames not supported: peer did not advertise max_datagram_frame_size > 0"));
                return future;
            }
            if (this.quicChannel == null) {
                future.failed(new IllegalStateException("QuicChannel not yet created; handshake may not be complete"));
                return future;
            }

            // Double-checked locking to avoid creating the DATAGRAM child channel more than once.
            synchronized (this) {
                if (this.datagramChannel != null) {
                    return Futures.buildCompleted(this.datagramChannel);
                }
                long chId = this.context.nextID();
                NetMonitor monitor = new NetMonitor();
                QuicDatagramChannelAsync asyncCh = new QuicDatagramChannelAsync(//
                        chId, this.quicChannel, this.context);
                QuicDatagramChannel datagramCh = new QuicDatagramChannel(//
                        chId, monitor, this.forListen, this.initializer, asyncCh, this.context, this.quicChannel);
                this.context.initChannel(datagramCh, true);
                this.datagramChannel = datagramCh;  // Volatile write, visible to all threads.
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
            // On the client side, ownerUdp belongs exclusively to this QUIC connection.
            // On the server side, ownerUdp is a shared DatagramChannel managed by NetListen/UdpAsyncServerChannel.
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
            this.streamStates.clear();
            this.streamIds.clear();
            this.datagramChannel = null;
        }

        // Phase 1: close the connection-level QuicChannel.
        // Phase 2: submit independent close tasks for each child channel so they can run concurrently.
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

        // Propagate the exception through the pipeline to all still-open child channels.
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
        if (!this.isMessageMuxMode()) {
            throw new UnsupportedOperationException("use QuicDatagramChannel or QuicStreamChannelAsync to write.");
        }
        if (wContext.isEmpty()) {
            return;
        }
        if (this.messageWriting.compareAndSet(false, true)) {
            this.context.submitSoTask(new QuicMessageWriteTask(channel, this, wContext, this.context), this).onFinal(f -> this.onMessageWriteTaskFinished());
        }
    }

    private static byte[] buildStreamData(long streamId, long offset, byte[] data, boolean fin) {
        int type = QuicFrameType.STREAM_BASE | QuicFrameType.STREAM_LEN_BIT;
        if (fin) {
            type |= QuicFrameType.STREAM_FIN_BIT;
        }
        if (offset > 0) {
            type |= QuicFrameType.STREAM_OFF_BIT;
        }

        byte[] typeBytes = QuicVarInt.encode(type);
        byte[] streamIdBytes = QuicVarInt.encode(streamId);
        byte[] offsetBytes = offset > 0 ? QuicVarInt.encode(offset) : new byte[0];
        byte[] lengthBytes = QuicVarInt.encode(data.length);
        int totalLen = typeBytes.length + streamIdBytes.length + offsetBytes.length + lengthBytes.length + data.length;
        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(streamIdBytes, 0, frame, pos, streamIdBytes.length);
        pos += streamIdBytes.length;
        if (offset > 0) {
            System.arraycopy(offsetBytes, 0, frame, pos, offsetBytes.length);
            pos += offsetBytes.length;
        }
        System.arraycopy(lengthBytes, 0, frame, pos, lengthBytes.length);
        pos += lengthBytes.length;
        if (data.length > 0) {
            System.arraycopy(data, 0, frame, pos, data.length);
        }
        return frame;
    }

    static class PreparedQuicMessage {
        private final QuicStreamState state;
        private final byte[]          frame;
        private final int             readableBytes;

        PreparedQuicMessage(QuicStreamState state, byte[] frame, int readableBytes) {
            this.state = state;
            this.frame = frame;
            this.readableBytes = readableBytes;
        }

        byte[] getFrame() {
            return this.frame;
        }

        void onSent() {
            if (this.readableBytes > 0) {
                this.state.addSendOffset(this.readableBytes);
            }
            this.state.touchActivity();
        }
    }

    static class QuicStreamState {
        private final long       streamId;
        private final AtomicLong sendOffset;
        private volatile long    maxDataSize;
        private volatile long    lastActivityTime;

        QuicStreamState(long streamId, long maxDataSize) {
            this.streamId = streamId;
            this.maxDataSize = maxDataSize;
            this.sendOffset = new AtomicLong(0);
            this.lastActivityTime = System.currentTimeMillis();
        }

        long getStreamId() {
            return this.streamId;
        }

        long getSendOffset() {
            return this.sendOffset.get();
        }

        void addSendOffset(long delta) {
            this.sendOffset.addAndGet(delta);
        }

        long getMaxDataSize() {
            return this.maxDataSize;
        }

        void updateMaxDataSize(long newMaxDataSize) {
            this.maxDataSize = Math.max(this.maxDataSize, newMaxDataSize);
        }

        long getLastActivityTime() {
            return this.lastActivityTime;
        }

        void touchActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }
    }

    /**
     * Wraps the given frame into a 1-RTT Short Header packet, encrypts it with application keys
     * when TLS is enabled, and sends it over UDP.
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
            long pn = this.handshake.getLastSndAppPacketNumber(); // PN used in the packet just built
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
            // Close directly without calling closeWithError to avoid recursive sendDataFrame calls
            this.closed.set(true);
            closeAllStreams();
            if (future != null) {
                future.failed(e);
            }
            return 0;
        }
    }

    /**
     * Sends a PING frame and returns a future that completes with the RTT in milliseconds;
     * timeoutMs=0 means wait indefinitely.
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
            long pn = this.handshake.getLastSndAppPacketNumber(); // PN of the just-sent PING packet
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
     * Parses already-decrypted 1-RTT payload frames and dispatches STREAM, DATAGRAM, and control
     * frames to their corresponding handlers.
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
                this.connectionMaxData = Math.max(this.connectionMaxData, tmp[0]);
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
                QuicStreamState state = this.streamStates.get(streamId);
                if (state != null) {
                    state.updateMaxDataSize(maxStreamData);
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] RESET_STREAM stream=" + streamId + " errorCode=" + errorCode);
                }
                QuicStreamResetException ex = new QuicStreamResetException(errorCode, streamId, finalSize);
                if (stream != null) {
                    this.context.notifyRcvChannelException(stream.getChannelId(), true, ex);
                } else if (this.isMessageMuxMode() && this.quicChannel != null) {
                    this.context.notifyRcvChannelException(this.quicChannel.getChannelId(), false, ex);
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] STOP_SENDING stream=" + streamId + " errorCode=" + errorCode);
                }
                QuicStopSendingException ex = new QuicStopSendingException(errorCode, streamId);
                if (stream != null) {
                    this.context.notifySndChannelException(stream.getChannelId(), false, ex);
                } else if (this.isMessageMuxMode() && this.quicChannel != null) {
                    this.context.notifySndChannelException(this.quicChannel.getChannelId(), false, ex);
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] CONNECTION_CLOSE errorCode=" + errorCode + " reason=" + reason);
                }

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
                        if (this.context.getConfig().isPrintLog()) {
                            logger.info("[QUIC] NEW_TOKEN len=" + tokenLen);
                        }
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] DATA_BLOCKED at " + maximumData + " bytes");
                }
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] STREAM_DATA_BLOCKED stream=" + streamId + " at " + maximumStreamData + " bytes");
                }
                QuicStreamChannel stream = this.streamMap.get(streamId);
                if (stream != null) {
                    // Auto-expand stream flow control
                    long newMax = Math.max(maximumStreamData * 2, stream.getMaxDataSize() * 2);
                    byte[] maxStreamDataFrame = QuicFlowControl.buildMaxStreamDataFrame(streamId, newMax);
                    this.sendDataFrame(ByteBuf.wrap(maxStreamDataFrame), null);
                    stream.updateMaxDataSize(newMax);
                } else {
                    QuicStreamState state = this.streamStates.get(streamId);
                    if (state != null) {
                        long newMax = Math.max(maximumStreamData * 2, state.getMaxDataSize() * 2);
                        byte[] maxStreamDataFrame = QuicFlowControl.buildMaxStreamDataFrame(streamId, newMax);
                        this.sendDataFrame(ByteBuf.wrap(maxStreamDataFrame), null);
                        state.updateMaxDataSize(newMax);
                    }
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] STREAMS_BLOCKED " + (bidi ? "bidi" : "uni") + " at " + maximumStreams);
                }
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
        long pn = this.handshake.getLastRcvAppPacketNumber();
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
     * Processes post-handshake CRYPTO data received in 1-RTT packets, such as NewSessionTicket
     * (0x04). Per RFC 9001 §6 a peer that receives a TLS KeyUpdate (0x18) over QUIC MUST close
     * the connection with a CRYPTO_ERROR carrying the TLS {@code unexpected_message} alert.
     */
    private void handlePostHandshakeCrypto(byte[] data) {
        if (data == null || data.length < 4) {
            return;
        }
        int msgType = data[0] & 0xFF;
        if (msgType == 0x18) {
            // RFC 9001 §6: "Endpoints MUST NOT send KeyUpdate messages; a peer that receives such
            // a message MUST treat it as a connection error of type 0x010a".
            logger.warn("[QUIC] received forbidden TLS KeyUpdate over QUIC; closing with CRYPTO_ERROR(0x010a) per RFC 9001 §6");
            QuicException ex = new QuicException(QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE,//
                    "TLS KeyUpdate is not allowed over QUIC (RFC 9001 §6)");
            notifyAllChannelsException(ex);
            this.closeWithError(QuicErrorCode.CRYPTO_ERROR_UNEXPECTED_MESSAGE,//
                    "TLS KeyUpdate forbidden over QUIC", null);
        } else if (msgType == 0x04) {
            // NewSessionTicket (TLS 1.3, RFC 8446 §4.6.1)
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] post-handshake NewSessionTicket len=" + data.length);
            }
            // Store session ticket for 0-RTT resumption (future enhancement)
        } else {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC] post-handshake CRYPTO type=0x" + Integer.toHexString(msgType));
            }
        }
    }

    /**
     * Parses a STREAM frame, auto-creates peer-initiated streams when necessary, and delivers the
     * data to the protocol pipeline; returns -1 on error.
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
        QuicStreamState streamState = this.streamStates.get(streamId);
        if (stream == null && streamState == null) {
            boolean clientInitiated = (streamId & 0x01) == 0;
            boolean locallyInitiated = (this.clientMode == clientInitiated);
            if (!locallyInitiated) {
                try {
                    if (this.isStreamChannelMode()) {
                        Future<QuicStreamChannel> f = newStreamChannel(streamId);
                        stream = f.getResult();
                        if (stream == null) {
                            logger.error("Failed to auto-create stream " + streamId + ": " + (f.getCause() != null ? f.getCause().getMessage() : "unknown"));
                            return pos + dataLength;
                        }
                        streamState = this.streamStates.get(streamId);
                    } else {
                        streamState = this.ensureInboundStreamState(streamId);
                    }
                } catch (Throwable e) {
                    logger.error("Failed to register stream state " + streamId + ": " + e.getMessage(), e);
                    return pos + dataLength;
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
            long streamMaxData = stream != null ? stream.getMaxDataSize() : streamState.getMaxDataSize();
            if (!this.flowControl.validateStreamData(offset, dataLength, streamMaxData)) {
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
            if (stream != null) {
                stream.touchActivity();
            }
            if (streamState != null) {
                streamState.touchActivity();
            }
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
                ByteBuf byteBuf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(contiguous.length);
                byteBuf.writeBytes(contiguous);
                byteBuf.markWriter();
                try {
                    if (this.isMessageMuxMode()) {
                        boolean streamComplete = reassembler.isComplete();
                        if (this.context.getConfig().isPrintLog()) {
                            logger.info("[QUIC-RCV] stream=" + streamId + " delivering " + contiguous.length + " bytes to connection mode, fin=" + streamComplete);
                        }
                        this.context.notifyRcvChannelData(this.quicChannel.getChannelId(), QuicMessage.of(streamId, byteBuf, streamComplete));
                    } else {
                        if (this.context.getConfig().isPrintLog()) {
                            logger.info("[QUIC-RCV] stream=" + streamId + " delivering " + contiguous.length + " bytes, ch=" + stream.getChannelId());
                        }
                        this.context.notifyRcvChannelData(stream.getChannelId(), byteBuf);
                    }
                } catch (Throwable t) {
                    logger.error("stream " + streamId + " pipeline delivery failed: " + t.getClass().getName() + ": " + t.getMessage(), t);
                }
            }
            // Check if stream is fully received (FIN delivered)
            if (reassembler.isComplete()) {
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC-RCV] stream=" + streamId + " FIN (stream complete)" + (this.isMessageMuxMode() ? " in message mode" : ", ch=" + stream.getChannelId()));
                }
                if (!this.isMessageMuxMode()) {
                    this.context.notifyRcvChannelData(stream.getChannelId(), ByteBuf.EMPTY);
                } else if (contiguous == null) {
                    this.context.notifyRcvChannelData(this.quicChannel.getChannelId(), QuicMessage.of(streamId, ByteBuf.EMPTY, true));
                }
                this.streamReassemblers.remove(streamId);
            }
        }
        pos += dataLength;

        // ── Auto-expand stream flow control window if needed ────────────
        long currentStreamMax = stream != null ? stream.getMaxDataSize() : streamState.getMaxDataSize();
        long newStreamMax = this.flowControl.shouldExpandStreamWindow(offset + dataLength, currentStreamMax);
        if (newStreamMax > 0) {
            byte[] maxStreamDataFrame = QuicFlowControl.buildMaxStreamDataFrame(streamId, newStreamMax);
            this.sendDataFrame(ByteBuf.wrap(maxStreamDataFrame), null);
            if (stream != null) {
                stream.updateMaxDataSize(newStreamMax);
            }
            if (streamState != null) {
                streamState.updateMaxDataSize(newStreamMax);
            }
        }

        return pos;
    }

    /**
     * Parses a DATAGRAM frame (RFC 9221 §5), auto-creates the datagram channel when needed, and
     * delivers the data; if negotiation has not completed, it is handled as PROTOCOL_VIOLATION.
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
        if (this.connectionDatagramMaxData == 0) {
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
            ByteBuf byteBuf = ByteBufUtils.DEFAULT_ALLOCATOR.buffer(dataLength);
            byteBuf.writeBytes(data, pos, dataLength);
            byteBuf.markWriter();
            this.context.notifyRcvChannelData(dgCh.getChannelId(), byteBuf);
        }
        pos += dataLength;
        return pos;
    }

    /**
     * Propagates a QUIC exception as a receive-side error to all open child channel pipelines,
     * including stream and datagram channels, so the application can observe it.
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

    /**
     * Checks and executes idle timeouts at both connection level and per-stream level.
     */
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] connection idle timeout " + elapsed + "ms (limit=" + connIdleTimeout + "ms)");
                }
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
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("[QUIC] stream " + stream.getStreamId() + " idle timeout " + streamElapsed + "ms (limit=" + streamIdleTimeout + "ms)");
                }
                QuicIdleTimeoutException ex = new QuicIdleTimeoutException(//
                        "Stream " + stream.getStreamId() + " idle timeout: " + streamElapsed + "ms >= " + streamIdleTimeout + "ms", false);
                this.context.notifyRcvChannelException(stream.getChannelId(), true, ex);
            }
        }
    }

    /**
     * Actively initiates connection migration, see RFC 9000 §9.
     */
    Future<Long> migrate() {
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

    /**
     * Lightweight object representing a pending PING request, holding the future to complete and
     * the send timestamp.
     */
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