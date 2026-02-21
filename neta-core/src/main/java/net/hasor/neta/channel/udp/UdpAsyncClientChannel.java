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
package net.hasor.neta.channel.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * UDP client channel implementation.
 * <p>
 * The UdpAsyncClientChannel supports reading data into a {@link ByteBuffer} with or without
 * a specified timeout.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see DatagramChannel
 * @see ExecutorService
 */
class UdpAsyncClientChannel extends UdpAsyncChannel {
    private static final Logger           logger = Logger.getLogger(UdpAsyncClientChannel.class);
    private final        UdpTransport     transport;
    private final        ByteBufAllocator bufAllocator;

    UdpAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);

        this.bufAllocator = this.context.getByteBufAllocator();
        int rcvPacketSize = UdpSoConfigUtils.getRcvPacketSize(this.soConfig);
        this.transport = UdpTransport.wrap(channelId, channel, this.context, rcvPacketSize, this);
    }

    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("udpClientSide(" + this.getChannelId() + ") close.");
        }
        this.transport.close();
    }

    //

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        // connect to
        try {
            this.transport.connect(this.remoteAddress);
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
            return;
        }

        // create channel
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

        // init & start read loop
        try {
            this.context.initChannel(channel, true);
            future.completed(channel);

            // start read loop via transport
            this.transport.startReceiveLoop((remoteAddr, data) -> this.onDatagram(channel, remoteAddr, data), () -> {
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

    private void onDatagram(UdpChannel channel, SocketAddress remoteAddr, ByteBuffer data) throws IOException {
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

        channel.setAttribute(UdpIdentifier.class.getName(), new UdpIdentifier(remoteID));
        return channel;
    }
}
