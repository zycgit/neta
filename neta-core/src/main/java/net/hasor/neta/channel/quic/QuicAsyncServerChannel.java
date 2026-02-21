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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpTransport;

/**
 * QUIC server channel. Uses {@link UdpTransport} for the underlying UDP
 * datagram I/O and manages per-client {@link QuicConnection} instances.
 * <p>
 * bind() creates a UdpTransport and starts a task-driven receive loop that:
 * <ol>
 *   <li>Reads UDP datagrams (via UdpTransport)</li>
 *   <li>Looks up (or creates) a QuicConnection for the remote peer</li>
 *   <li>Delegates packet processing to the QuicConnection</li>
 * </ol>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncServerChannel implements AsyncServerChannel {
    private static final Logger           logger = Logger.getLogger(QuicAsyncServerChannel.class);
    private final        long             channelId;
    private final        SoContextService context;
    private final        SocketAddress    listenAddr;
    private final        QuicSoConfig     soConfig;
    private final        AtomicBoolean    closed = new AtomicBoolean(false);
    // Underlying UDP transport (replaces raw DatagramChannel + Selector + Thread)
    private              UdpTransport     transport;

    // Per-remote-peer QUIC connections
    private final Map<SocketAddress, QuicConnection> connections = new ConcurrentHashMap<>();

    // The QuicListen created by bind()
    private QuicListen quicListen;

    public QuicAsyncServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.context = (SoContextService) context;
        this.listenAddr = listenAddr;
        this.soConfig = (QuicSoConfig) soConfig;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && this.transport != null && this.transport.isOpen();
    }

    @Override
    public synchronized NetListen bind(ProtoInitializer initializer) throws IOException {
        if (this.quicListen != null) {
            return this.quicListen;
        }

        // Create UDP transport
        this.transport = UdpTransport.open(this.channelId, this.context, this.soConfig.getRcvPacketSize(), this);

        // Configure socket options before binding
        this.transport.getChannel().socket().setReuseAddress(true);

        // Bind + configureBlocking(false) + register selector
        this.transport.bind(this.listenAddr);

        SocketAddress actualAddr = this.transport.getLocalAddress();
        int listenPort = (actualAddr instanceof InetSocketAddress) ? ((InetSocketAddress) actualAddr).getPort() : 0;

        this.quicListen = new QuicListen(this.channelId, actualAddr, listenPort, this, initializer, this.context, this.soConfig);

        // Register listen with the framework (like TCP/UDP do)
        try {
            this.context.initChannel(this.quicListen, false);
        } catch (Throwable e) {
            throw new IOException("Failed to register QUIC listen channel", e);
        }

        // Start task-driven receive loop via UdpTransport
        this.transport.startReceiveLoop((remoteAddr, data) -> this.onDatagram(remoteAddr, data), () -> {
            if (!this.closed.get()) {
                logger.info("QUIC server transport closed on " + this.listenAddr);
            }
        }, (e) -> {
            if (!this.closed.get()) {
                logger.error("QUIC receive loop error: " + e.getMessage());
            }
        });

        logger.info("QUIC server listening on " + actualAddr + " (SSL=" + this.soConfig.isSslEnabled() + ")");
        return this.quicListen;
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            // Close all connections
            for (QuicConnection conn : this.connections.values()) {
                conn.close();
            }
            this.connections.clear();

            // Close the UDP transport (handles DatagramChannel + Selector + buffer cleanup)
            if (this.transport != null) {
                this.transport.close();
            }

            logger.info("QUIC server closed on " + this.listenAddr);
        }
    }

    // ── Datagram Handling ──────────────────────────────────────────────

    private void onDatagram(SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        int len = data.remaining();
        byte[] bytes = new byte[len];
        data.get(bytes);

        processIncoming(remoteAddr, bytes, 0, len);
    }

    private void processIncoming(SocketAddress remoteAddr, byte[] data, int offset, int length) {
        QuicConnection conn = this.connections.get(remoteAddr);

        if (conn == null) {
            // New connection — create one
            byte[] srcConnId = QuicConnection.generateConnectionId(this.soConfig.getConnectionIdLength());
            SocketAddress localAddr = this.quicListen != null ? getLocalAddress() : this.listenAddr;
            conn = new QuicConnection(srcConnId, remoteAddr, localAddr, this.transport.getChannel(), this.soConfig, this.context, this.quicListen);

            QuicConnection existing = this.connections.putIfAbsent(remoteAddr, conn);
            if (existing != null) {
                conn = existing;
            }
        }

        conn.processPacket(data, offset, length);
    }

    private SocketAddress getLocalAddress() {
        try {
            return this.transport.getLocalAddress();
        } catch (Exception e) {
            return this.listenAddr;
        }
    }

    /** Get the map of active connections (for testing/monitoring). */
    public Map<SocketAddress, QuicConnection> getConnections() {
        return this.connections;
    }
}
