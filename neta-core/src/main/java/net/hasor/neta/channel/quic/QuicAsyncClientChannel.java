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
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpTransport;

/**
 * QUIC client-side {@link AsyncChannel}.
 * <p>
 * Uses {@link UdpTransport} for the underlying UDP I/O, performs the QUIC handshake
 * with the remote server, creates a {@link QuicChannel} (connection-level), and
 * starts a task-driven receive loop.
 * <p>
 * The connection handshake is asynchronous — {@code connectTo()} initiates the handshake
 * and the provided {@link Future} completes when ESTABLISHED state is reached.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncClientChannel implements AsyncChannel {
    private static final Logger           logger = Logger.getLogger(QuicAsyncClientChannel.class);
    private final        long             channelId;
    private final        SoContextService context;
    private final        SocketAddress    remoteAddr;
    private final        QuicSoConfig     soConfig;
    private final        AtomicBoolean    closed = new AtomicBoolean(false);
    private              UdpTransport     transport;
    private              QuicConnection   quicConn;

    public QuicAsyncClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.context = (SoContextService) context;
        this.remoteAddr = remoteAddr;
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
    public SocketAddress getLocalAddress() {
        try {
            return this.transport != null ? this.transport.getLocalAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddr;
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && this.transport != null && this.transport.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            if (this.quicConn != null) {
                this.quicConn.close();
            }
            if (this.transport != null) {
                this.transport.close();
            }
        }
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        // Writing is handled by QuicAsyncConnectionChannel, not this class.
        // This should not be called directly.
        wContext.purge(new IOException("Write via QuicAsyncConnectionChannel, not QuicAsyncClientChannel"));
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
        try {
            // 1. Create UDP transport and connect
            this.transport = UdpTransport.open(this.channelId, this.context, this.soConfig.getRcvPacketSize(), this);
            this.transport.connect(this.remoteAddr);

            SocketAddress localAddr = this.transport.getLocalAddress();

            // 2. Create QUIC connection (client mode)
            byte[] srcConnId = QuicConnection.generateConnectionId(this.soConfig.getConnectionIdLength());
            this.quicConn = new QuicConnection(srcConnId, this.remoteAddr, localAddr, this.transport.getChannel(), this.soConfig, this.context, null,          // no listen for client
                    true,          // client mode
                    initializer    // stored for channel creation
            );

            // 3. Send Initial packet to start handshake
            this.quicConn.sendClientInitial();

            // 4. Start task-driven receive loop — handshake completes asynchronously
            final AtomicBoolean channelReported = new AtomicBoolean(false);
            this.transport.startReceiveLoop((remoteAddr, data) -> {
                int len = data.remaining();
                byte[] bytes = new byte[len];
                data.get(bytes);
                this.quicConn.processPacket(bytes, 0, len);
                // Check if handshake just completed
                if (!channelReported.get() && tryReportChannel(future)) {
                    channelReported.set(true);
                }
            }, () -> {
                if (!this.closed.get()) {
                    logger.info("QUIC client transport closed for " + this.remoteAddr);
                }
            }, (e) -> {
                if (!this.closed.get()) {
                    logger.error("QUIC client receive error: " + e.getMessage());
                }
            });

        } catch (Throwable e) {
            logger.error("QUIC client connect failed: " + e.getMessage(), e);
            close();
            future.failed(e);
        }
    }

    private boolean tryReportChannel(Future<NetChannel> future) {
        if (this.quicConn.getHandshakeState() == QuicConnection.HandshakeState.ESTABLISHED) {
            QuicChannel ch = this.quicConn.getQuicChannel();
            if (ch != null) {
                future.completed(ch);
                return true;
            }
        }
        return false;
    }
}
