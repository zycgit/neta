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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * An implementation of the {@link AsyncServerChannel} interface for UDP communication.
 * This class provides asynchronous accept operations over a UDP channel,
 * using a non-blocking {@link DatagramChannel} and a dedicated I/O executor service.
 * <p>
 * The UdpAsyncServerChannel supports accepting incoming connections with a specified timeout.
 * Upon accepting a connection, it creates a new {@link UdpAsyncChannel} for the accepted socket,
 * and binds it to the provided {@link SoContext}.
 * <p>
 * The I/O operations for the accepted channels are performed by the provided I/O executor service.
 *
 * @see java.nio.channels.DatagramChannel
 * @see java.util.concurrent.ExecutorService
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class UdpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger          logger = Logger.getLogger(UdpAsyncServerChannel.class);
    private final        long            channelID;
    private final        DatagramChannel channel;
    private              Selector        selector;
    private final        ExecutorService ioExecutor;

    UdpAsyncServerChannel(long channelID, DatagramChannel channel, ExecutorService ioExecutor) throws IOException {
        this.channelID = channelID;
        this.channel = channel;
        this.ioExecutor = ioExecutor;

    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public NetworkChannel getChannel() {
        return this.channel;
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(this.channel);
        IOUtils.closeQuietly(this.selector);
    }

    @Override
    public void bind(NetListen listen, SoContext context, NetOptions options) throws IOException {
        this.selector = Selector.open();
        this.channel.bind(listen.getLocalAddr());
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);

        int rcvPacketSize = context.getConfig().getSoRcvBuf();
        if (options instanceof UdpOptions) {
            Integer packetSize = ((UdpOptions) options).getRcvPacketSize();
            if (packetSize != null) {
                rcvPacketSize = packetSize;
            }
        }

        int finalRcvPacketSize = rcvPacketSize;
        this.ioExecutor.execute(() -> this.receiveData(listen, context, finalRcvPacketSize));
    }

    private void receiveData(NetListen listen, SoContext context, int packetSize) {
        ByteBufAllocator allocator = context.getByteBufAllocator();
        ByteBuffer buffer = allocator.jvmBuffer(packetSize);
        Map<String, SoChannel<?>> channelMap = new ConcurrentHashMap<>();

        while (true) {
            if (listen.isClose()) {
                ByteBufUtils.CLEANER.freeDirectBuffer(buffer);
                return;
            }

            try {
                if (this.selector.select(100) == 0) {
                    continue;
                }

                Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isReadable()) {
                        buffer.clear();
                        DatagramChannel channel = (DatagramChannel) key.channel();

                        InetSocketAddress remoteAddr = (InetSocketAddress) channel.receive(buffer);
                        SoChannel<?> socket = this.findOrCreateChannel(listen, context, remoteAddr, channelMap);
                        if (socket != null) {
                            ByteBuf byteBuf = allocator.buffer(buffer.position());
                            buffer.flip();
                            byteBuf.writeBuffer(buffer);
                            byteBuf.markWriter();
                            ((SoContextService) context).notifyChannelRcv(socket.getChannelID(), byteBuf);
                        }
                    }

                    it.remove();
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private SoChannel<?> findOrCreateChannel(NetListen listen, SoContext context, InetSocketAddress remoteAddr, Map<String, SoChannel<?>> channelMap) {
        String remoteID = remoteAddr.getAddress().getHostAddress() + ":" + remoteAddr.getPort();
        SoChannel<?> socket = channelMap.get(remoteID);
        if (socket != null) {
            return socket;
        }

        try {
            long channelId = ((SoContextService) context).nextID();
            socket = ((SoContextService) context).initChannel(listen, new UdpAsyncChannel(channelId, remoteAddr, this.channel));
            socket.setAttribute(UdpIdentifier.class.getName(), new UdpIdentifier(remoteID));

            channelMap.put(remoteID, socket);
            socket.onClose(channel -> channelMap.remove(channel.getAttribute(UdpIdentifier.class.getName()).toString()));
            return socket;
        } catch (SoRejectException e) {
            logger.info("reject remote " + remoteID);
            return null;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }
}