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
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * Connected UDP client transport built on top of {@link UdpTransport}.
 * <p>After {@link #connectTo(ProtoInitializer, Future)} is called, it creates an
 * application-facing {@link UdpChannel}, starts the receive loop on the shared transport layer,
 * and forwards every datagram from the connected peer into the pipeline.
 * <p>When {@link UdpSoConfig#isRcvRemoteOnly()} is enabled, the client receive path discards any
 * datagram that does not come from the configured remote address.
 * <p><b>Client flow:</b>
 * <pre>
 *   connectTo(initializer, future)
 *                 ▼
 *      transport.connect(remoteAddress)
 *                 ▼
 *      create UdpAsyncChannel + UdpChannel
 *                 ▼
 *         context.initChannel(channel)
 *                 ▼
 *         future.completed(channel)
 *                 ▼
 *      transport.startReceiveLoop(...)
 *                 ▼
 *      onDatagram(channel, remoteAddr, data)
 *       ┌─────────┴─────────────────────────────┐
 *       ▼                                       ▼
 *   rcvRemoteOnly=true and                   allowed to receive
 *   remoteAddr != configured remote             │
 *       └── discard and return                  ▼
 *                                   ByteBuffer -> ByteBuf
 *                                               ▼
 *                                  notifyRcvChannelData(...)
 * </pre>
 * <p><b>Failure handling:</b> if an exception occurs during connect, channel creation, or
 * initialization, the future is failed and the context is notified about the connection error.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see DatagramChannel
 */
public class UdpAsyncClientChannel extends UdpAsyncChannel {
    private static final Logger           logger = Logger.getLogger(UdpAsyncClientChannel.class);
    protected final      UdpTransport     transport;
    protected final      ByteBufAllocator bufAllocator;

    /**
     * Create a UDP client asynchronous channel.
     * @param channelId the channel ID
     * @param channel the underlying DatagramChannel
     * @param context the runtime context
     * @param remoteAddress the remote address
     * @param soConfig the channel configuration
     * @throws IOException if an I/O error occurs during initialization
     */
    protected UdpAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);

        this.bufAllocator = this.context.getByteBufAllocator();
        int rcvPacketSize = UdpSoConfigUtils.getRcvPacketSize(this.soConfig);
        this.transport = UdpTransport.wrap(channelId, channel, this.context, rcvPacketSize, this);
    }

    /**
     * Close the client transport and its internal receive loop.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("udpClientSide(" + this.getChannelId() + ") close.");
        }
        this.transport.close();
    }

    //

    /**
     * Establish the UDP client connection, create the framework channel, and start the receive loop.
     * @param initializer the protocol initializer
     * @param future the future used to return the connection result
     */
    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        // Perform the underlying connect.
        try {
            this.transport.connect(this.remoteAddress);
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
            return;
        }

        // Create the framework channel.
        UdpChannel channel;
        try {
            String remoteID = this.remoteAddress.getAddress().getHostAddress() + ":" + this.remoteAddress.getPort();
            UdpAsyncChannel asyncChannel = new UdpAsyncChannel(this.channelId, this.channel, this.context, this.remoteAddress, this.soConfig);
            channel = this.newChannel(remoteID, asyncChannel, initializer);
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
            return;
        }

        // Initialize the channel and start the receive loop.
        try {
            this.context.initChannel(channel, true);
            future.completed(channel);

            // Start the receive loop through the transport.
            this.transport.startReceiveLoop((remoteAddr, data) -> this.onDatagram(channel, remoteAddr, data), channel::isClose, () -> {
                logger.info("rcv(" + this.channelId + ") close form local.");
                this.context.notifyChannelClose(channel.getChannelId(), false);
            }, (e) -> {
                SoRcvException err = new SoRcvException(e.getMessage(), e);
                this.context.notifyRcvChannelException(channel.getChannelId(), false, err);
            });
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(channel.getChannelId(), true, ee);
            future.failed(e);
        }
    }

    /**
     * Handle one received UDP datagram.
     * @param channel the target framework channel
     * @param remoteAddr the actual remote address
     * @param data the data received this time
     * @throws IOException if an I/O error occurs during processing
     */
    protected void onDatagram(UdpChannel channel, SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        InetSocketAddress inetRemoteAddr = (InetSocketAddress) remoteAddr;
        if (this.soConfig.isRcvRemoteOnly() && !inetRemoteAddr.equals(this.remoteAddress)) {
            return;
        }

        int remaining = data.remaining();
        ByteBuf byteBuf = this.bufAllocator.buffer(remaining);
        byteBuf.writeBuffer(data);
        byteBuf.markWriter();
        int readableBytes = byteBuf.readableBytes();
        if (logger.isDebugEnabled()) {
            logger.debug("rcv(" + this.channelId + ") the receive " + readableBytes + " bytes");
        }

        channel.getNetMonitor().updateRcvCounter(readableBytes);
        this.context.notifyRcvChannelData(channel.getChannelId(), byteBuf);
    }

    /**
     * Create the framework-level {@link UdpChannel} for a connected client.
     * @param remoteID the remote identifier
     * @param realChannel the underlying asynchronous channel
     * @param initializer the protocol initializer
     * @return the newly created UdpChannel
     * @throws IOException if an I/O error occurs during creation
     */
    protected UdpChannel newChannel(String remoteID, UdpAsyncChannel realChannel, ProtoInitializer initializer) throws IOException {
        NetMonitor monitor = new NetMonitor();
        UdpChannel channel = new UdpChannel(//
                realChannel.getChannelId(), //
                monitor,                    //
                null,                       //
                initializer,                //
                realChannel,                //
                this.context                //
        );

        return channel;
    }
}
