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
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpAsyncClientChannel;
import net.hasor.neta.channel.udp.UdpChannel;
import net.hasor.neta.channel.udp.UdpSoConfigUtils;

/**
 * QUIC client channel extending {@link UdpAsyncClientChannel}.
 * <p>
 * Inherits the UDP transport layer and overrides the connection flow
 * to perform QUIC handshake:
 * <ol>
 *   <li>Creates UDP transport and connects</li>
 *   <li>Creates {@link QuicChannel} (client mode)</li>
 *   <li>Sends Initial packet to start handshake</li>
 *   <li>Starts receive loop — handshake completes asynchronously</li>
 * </ol>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncClientChannel extends UdpAsyncClientChannel {
    private static final Logger      logger = Logger.getLogger(QuicAsyncClientChannel.class);
    private              QuicChannel quicChannel;

    protected QuicAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);
    }

    @Override
    public void close() throws IOException {
        if (this.quicChannel != null) {
            this.quicChannel.close();
        }
        super.close();
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        // Writing is handled by QuicAsyncConnectionChannel, not this class.
        wContext.purge(new IOException("Write via QuicAsyncConnectionChannel, not QuicAsyncClientChannel"));
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        QuicSoConfig quicConfig = (QuicSoConfig) this.soConfig;

        try {
            // 1. Connect UDP transport
            this.transport.connect(this.remoteAddress);
            UdpSoConfigUtils.configSocket(this.soConfig, this.transport.getChannel());

            SocketAddress localAddr = this.transport.getLocalAddress();

            // 2. Create QUIC channel (client mode with owned transport)
            byte[] srcConnId = QuicChannel.generateConnectionId(quicConfig.getConnectionIdLength());
            this.quicChannel = new QuicChannel(srcConnId, this.remoteAddress, localAddr, this.transport, quicConfig, this.context, initializer);

            // 3. Send Initial packet to start handshake
            this.quicChannel.sendClientInitial();

            // 4. Start task-driven receive loop — handshake completes asynchronously
            final AtomicBoolean channelReported = new AtomicBoolean(false);
            this.transport.setSelectorPollMs(quicConfig.getSelectorPollMs());
            this.transport.startReceiveLoop((remoteAddr, data) -> {
                int len = data.remaining();
                byte[] bytes = new byte[len];
                data.get(bytes);
                this.quicChannel.processPacket(bytes, 0, len);
                // Check if handshake just completed
                if (!channelReported.get() && tryReportChannel(future)) {
                    channelReported.set(true);
                }
            }, () -> {
                logger.info("QUIC client transport closed for " + this.remoteAddress);
                this.context.notifyChannelClose(this.quicChannel.getChannelId(), false);
            }, (e) -> {
                logger.error("QUIC client receive error: " + e.getMessage());
                this.context.notifyRcvChannelException(this.channelId, false, new SoRcvException(e.getMessage(), e));
            });

        } catch (Throwable e) {
            logger.error("QUIC client connect failed: " + e.getMessage(), e);
            try {
                close();
            } catch (IOException ignored) {
            }
            SoConnectException connErr = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(this.channelId, true, connErr);
            future.failed(connErr);
        }
    }

    private boolean tryReportChannel(Future<NetChannel> future) {
        if (this.quicChannel.getHandshakeState() == QuicChannel.HandshakeState.ESTABLISHED) {
            future.completed(this.quicChannel);
            return true;
        }
        return false;
    }

    @Override
    protected UdpChannel newChannel(String remoteID, net.hasor.neta.channel.udp.UdpAsyncChannel realChannel, ProtoInitializer initializer) throws IOException {
        // Not used in QUIC — QuicChannel is created directly in connectTo
        throw new UnsupportedOperationException("QUIC does not use UDP newChannel");
    }
}
