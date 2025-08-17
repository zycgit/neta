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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;

/**
 * An implementation of the {@link AsyncChannel} interface for UDP communication.
 * This class provides asynchronous read and write operations over a UDP channel,
 * using a non-blocking {@link DatagramChannel} and a dedicated I/O executor service.
 * <p>
 * The UdpAsyncChannel supports reading data into a {@link ByteBuffer} with or without
 * a specified timeout. It does not support writing data, as well as connecting to a remote
 * address, which are unsupported operations for this type of channel.
 * <p>
 * Upon creation, the channel is registered with a selector for reading, and all I/O operations
 * are performed by the provided I/O executor service.
 *
 * @see DatagramChannel
 * @see ExecutorService
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class UdpAsyncClientChannel extends UdpAsyncChannel {
    private static final Logger           logger = Logger.getLogger(UdpAsyncClientChannel.class);
    private final        Selector         readSelector;
    //
    private final        ByteBufAllocator bufAllocator;
    private final        ByteBuffer       receiveBuffer;

    UdpAsyncClientChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        super(channelId, channel, context, remoteAddress, soConfig);
        this.readSelector = Selector.open();

        this.bufAllocator = this.context.getByteBufAllocator();
        this.receiveBuffer = this.bufAllocator.jvmBuffer(UdpSoConfigUtils.getRcvPacketSize(this.soConfig));
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(this.readSelector);
        super.close();
    }

    //

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        try {
            // connect to
            this.channel.connect(this.remoteAddress);
            this.channel.configureBlocking(false);
            this.channel.register(this.readSelector, SelectionKey.OP_READ);

            // create channel
            String remoteID = this.remoteAddress.getAddress().getHostAddress() + ":" + this.remoteAddress.getPort();
            UdpAsyncChannel asyncChannel = new UdpAsyncChannel(this.channelID, this.channel, this.context, this.remoteAddress, this.soConfig);
            UdpChannel channel = this.newChannel(remoteID, asyncChannel, initializer);

            // init
            this.context.initChannel(channel, true);
            future.completed(channel);

            // start read loop
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                this.receiveLoop(channel);
            });
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
        }
    }

    private void receiveLoop(UdpChannel channel) {
        if (!this.channel.isOpen()) {
            return;
        }

        try {
            this.receiveData(channel);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.receiveLoop(channel);
        });
    }

    private void receiveData(UdpChannel channel) throws IOException {
        //long startTime = System.currentTimeMillis();
        //if (timeoutMs > 0) {
        //    long remainingTimeout = timeoutMs - (System.currentTimeMillis() - startTime);
        //    if (remainingTimeout <= 0) {
        //        handler.failed(new SoReadTimeoutException("socket read timeout"), attachment);
        //        return;
        //    }
        //    this.selector.select(remainingTimeout);
        //} else {
        //    this.selector.select();
        //}
        if (this.readSelector.select(100) == 0) {
            return;
        }

        Iterator<SelectionKey> it = this.readSelector.selectedKeys().iterator();
        while (it.hasNext()) {
            // pull key
            SelectionKey key = it.next();
            it.remove();

            // process
            if (key.isReadable()) {
                DatagramChannel socket = (DatagramChannel) key.channel();

                this.receiveBuffer.clear();
                InetSocketAddress remoteAddr = (InetSocketAddress) socket.receive(this.receiveBuffer);
                if (this.soConfig.isRcvRemoteOnly() && !remoteAddr.equals(this.remoteAddress)) {
                    continue; // in the UDP client mode, the remote address will be locked.
                }

                ByteBuf byteBuf = this.bufAllocator.buffer(this.receiveBuffer.position());
                this.receiveBuffer.flip();
                byteBuf.writeBuffer(this.receiveBuffer);
                byteBuf.markWriter();
                int readableBytes = byteBuf.readableBytes();
                if (logger.isDebugEnabled()) {
                    logger.debug("rcv(" + this.channelID + ") the receive " + readableBytes + " bytes");
                }

                channel.getNetMonitor().updateRcvCounter(readableBytes);
                this.context.notifyChannelRcv(channel.getChannelID(), byteBuf);
            }
        }
    }

    protected UdpChannel newChannel(String remoteID, UdpAsyncChannel realChannel, ProtoInitializer initializer) throws IOException {
        NetMonitor monitor = new NetMonitor();
        UdpChannel channel = new UdpChannel(//
                realChannel.getChannelID(), //
                monitor,                    //
                null,                       //
                initializer,                //
                realChannel,                //
                this.context                //
        );

        channel.setAttribute(UdpIdentifier.class.getName(), new UdpIdentifier(remoteID));
        return channel;
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}
