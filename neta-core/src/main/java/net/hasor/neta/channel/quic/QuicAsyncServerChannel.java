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
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpAsyncServerChannel;
import net.hasor.neta.channel.udp.UdpChannel;
import net.hasor.neta.channel.udp.UdpSoConfigUtils;

/**
 * QUIC server channel extending {@link UdpAsyncServerChannel}.
 * <p>
 * Inherits the UDP transport layer (bind, receive loop, socket configuration)
 * and overrides datagram handling to support QUIC protocol:
 * <ul>
 *   <li>Manages per-remote-peer {@link QuicChannel} instances</li>
 *   <li>Delegates packet processing to QuicChannel</li>
 *   <li>Performs accept filtering before creating connections</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncServerChannel extends UdpAsyncServerChannel {
    private static final Logger logger = Logger.getLogger(QuicAsyncServerChannel.class);

    // Per-remote-peer QUIC channels (replaces the UDP channelMap)
    private final Map<SocketAddress, QuicChannel> connections = new ConcurrentHashMap<>();

    protected QuicAsyncServerChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        super(channelId, channel, context, listenAddr, soConfig);
    }

    @Override
    public void close() throws IOException {
        // Close all QUIC connections before closing the transport
        for (QuicChannel conn : this.connections.values()) {
            conn.close();
        }
        this.connections.clear();
        super.close();
    }

    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // Configure socket
        UdpSoConfigUtils.configListen(this.soConfig, this.transport.getChannel());

        // Create QUIC-specific listen
        QuicSoConfig quicConfig = (QuicSoConfig) this.soConfig;
        SocketAddress actualAddr;
        int listenPort;

        try {
            this.transport.bind(this.listenAddr);
            actualAddr = this.transport.getLocalAddress();
            listenPort = (actualAddr instanceof InetSocketAddress) ? ((InetSocketAddress) actualAddr).getPort() : 0;
        } catch (Throwable e) {
            SoBindException bindErr = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, bindErr);
            throw new IOException("Failed to bind QUIC server on " + this.listenAddr, bindErr);
        }

        QuicListen listen = new QuicListen(//
                this.channelId,     //
                actualAddr,         //
                listenPort,         //
                this,               //
                initializer,        //
                this.context,       //
                this.soConfig);

        try {
            this.context.initChannel(listen, false);
        } catch (Throwable e) {
            SoBindException bindErr = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, bindErr);
            throw new IOException("Failed to init QUIC listen on " + this.listenAddr, bindErr);
        }

        // Start task-driven receive loop via UdpTransport
        this.transport.setSelectorPollMs(quicConfig.getSelectorPollMs());
        final SocketAddress finalLocalAddr = actualAddr;
        this.transport.startReceiveLoop(//
                (remoteAddr, data) -> this.onQuicDatagram(listen, finalLocalAddr, remoteAddr, data),//
                () -> {
                    logger.info("QUIC server transport closed on " + this.listenAddr);
                    this.context.notifyChannelClose(this.channelId, false);
                },//
                (e) -> {
                    logger.error("QUIC receive loop error: " + e.getMessage());
                    this.context.notifyRcvChannelException(this.channelId, false, new SoRcvException(e.getMessage(), e));
                });

        logger.info("QUIC server listening on " + actualAddr + " (SSL=" + quicConfig.isSslEnabled() + ")");
        return listen;
    }

    // ── QUIC Datagram Handling ─────────────────────────────────────────

    private void onQuicDatagram(QuicListen listen, SocketAddress localAddr, SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        int len = data.remaining();
        byte[] bytes = new byte[len];
        data.get(bytes);

        processIncoming(listen, localAddr, remoteAddr, bytes, 0, len);
    }

    private void processIncoming(QuicListen listen, SocketAddress localAddr, SocketAddress remoteAddr, byte[] data, int offset, int length) {
        QuicChannel conn = this.connections.get(remoteAddr);

        if (conn == null) {
            // Check if listen is suspended
            if (listen.isSuspend()) {
                printLog("AcceptFailed, listen is suspended. R:" + remoteAddr);
                return;
            }

            // Apply accept filter
            if (!acceptChannel(listen, localAddr, remoteAddr)) {
                return;
            }

            // New connection — create one
            QuicSoConfig quicConfig = (QuicSoConfig) this.soConfig;
            byte[] srcConnId = QuicChannel.generateConnectionId(quicConfig.getConnectionIdLength());
            long newChannelId;
            try {
                conn = new QuicChannel(srcConnId, remoteAddr, localAddr, this.transport.getChannel(), quicConfig, this.context, listen);
                newChannelId = conn.getChannelId();
            } catch (IOException e) {
                long tmpId = this.context.nextID();
                SoConnectException err = new SoConnectException(e.getMessage(), e);
                this.context.notifyConnectChannelException(tmpId, true, err);
                logger.error("Failed to create QuicChannel for " + remoteAddr + ": " + e.getMessage());
                return;
            }

            QuicChannel existing = this.connections.putIfAbsent(remoteAddr, conn);
            if (existing != null) {
                conn = existing;
            } else {
                // Register cleanup (mirrors UDP)
                final SocketAddress closedRemoteAddr = remoteAddr;
                conn.onClose(c -> this.connections.remove(closedRemoteAddr));
            }
        }

        conn.processPacket(data, offset, length);
    }

    /** Get the map of active connections (for testing/monitoring). */
    public Map<SocketAddress, QuicChannel> getConnections() {
        return this.connections;
    }

    // ── Overrides that are not used in QUIC mode ──────────────────────

    @Override
    protected UdpChannel newChannel(String remoteID, NetListen forListen, net.hasor.neta.channel.udp.UdpAsyncChannel realChannel) throws IOException {
        // Not used in QUIC — connections are created via processIncoming
        throw new UnsupportedOperationException("QUIC does not use UDP newChannel");
    }
}
