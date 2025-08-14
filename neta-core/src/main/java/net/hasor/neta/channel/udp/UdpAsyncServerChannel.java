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
import java.net.SocketAddress;
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
    private static final Logger            logger = Logger.getLogger(UdpAsyncServerChannel.class);
    private final        long              channelID;
    private final        DatagramChannel   channel;
    private final        Selector          selector;
    private final        ExecutorService   ioExecutor;
    private final        SoContextService  context;
    private final        InetSocketAddress listenAddr;
    private final        UdpSoConfig       soConfig;
    //
    private final        ByteBufAllocator  bufAllocator;
    private final        ByteBuffer        receiveBuffer;

    UdpAsyncServerChannel(long channelId, DatagramChannel channel, ExecutorService ioExecutor, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        this.channelID = channelId;
        this.channel = channel;
        this.selector = Selector.open();
        this.context = (SoContextService) context;
        this.ioExecutor = ioExecutor;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (UdpSoConfig) soConfig;

        this.bufAllocator = this.context.getByteBufAllocator();
        this.receiveBuffer = this.bufAllocator.jvmBuffer(UdpSoConfigUtils.getRcvPacketSize(this.soConfig));
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
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
    public NetListen bind(ProtoInitializer initializer) throws Throwable {
        // create
        UdpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new NetListen( //
                this.channelID,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // init
        this.context.initChannel(listen, false);

        // start
        this.channel.bind(this.listenAddr);
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);

        // start receive
        SocketAddress localAddr = this.channel.getLocalAddress();
        this.ioExecutor.execute(() -> this.receiveData(listen, localAddr));
        return listen;
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(this.channel);
        IOUtils.closeQuietly(this.selector);
        ByteBufUtils.CLEANER.freeDirectBuffer(this.receiveBuffer);
    }

    private void receiveData(NetListen listen, SocketAddress localAddr) {
        Map<String, UdpChannel> channelMap = new ConcurrentHashMap<>();

        while (this.channel.isOpen()) {
            try {
                if (this.selector.select(100) == 0) {
                    continue;
                }

                Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    // pull key
                    SelectionKey key = it.next();
                    it.remove();

                    // process
                    if (key.isReadable()) {
                        DatagramChannel channel = (DatagramChannel) key.channel();

                        this.receiveBuffer.clear();
                        InetSocketAddress remoteAddr = (InetSocketAddress) channel.receive(this.receiveBuffer);
                        UdpChannel socket = this.findOrCreateChannel(listen, localAddr, remoteAddr, channelMap);
                        if (socket != null) {
                            ByteBuf byteBuf = this.bufAllocator.buffer(this.receiveBuffer.position());
                            this.receiveBuffer.flip();
                            byteBuf.writeBuffer(this.receiveBuffer);
                            byteBuf.markWriter();
                            int readableBytes = byteBuf.readableBytes();
                            if (logger.isDebugEnabled()) {
                                logger.debug("rcv(" + this.channelID + ") the receive " + readableBytes + " bytes");
                            }

                            socket.getNetMonitor().updateRcvCounter(byteBuf.readableBytes());
                            this.context.notifyChannelRcv(socket.getChannelID(), byteBuf);
                        }
                    }
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private UdpChannel findOrCreateChannel(NetListen listen, SocketAddress localAddr, InetSocketAddress remoteAddr, Map<String, UdpChannel> channelMap) {
        if (listen.isSuspend()) {
            this.printLog("ERROR: AcceptFailed, listen is suspend.");
            return null;
        }

        String remoteID = remoteAddr.getAddress().getHostAddress() + ":" + remoteAddr.getPort();
        UdpChannel socket = channelMap.get(remoteID);
        if (socket != null) {
            return socket;
        }

        if (!this.acceptChannel(listen, localAddr, remoteAddr)) {
            return null;
        }

        try {
            // create
            long channelId = this.context.nextID();
            UdpChannel channel = this.newChannel(remoteID, listen, new UdpAsyncChannel(channelId, this.channel, this.context, remoteAddr, this.soConfig));

            // init
            this.context.initChannel(channel, true);

            //
            channelMap.put(remoteID, socket);
            socket.onClose(c -> channelMap.remove(c.getAttribute(UdpIdentifier.class.getName()).toString()));
            return channel;
        } catch (SoRejectException e) {
            logger.info("reject remote " + remoteID);
            return null;
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            return null;
        }
    }

    private boolean acceptChannel(NetListen listen, SocketAddress localAddr, SocketAddress remoteAddr) {
        try {
            if (!this.context.acceptChannel(remoteAddr)) {
                printLog("reject(" + listen.getChannelID() + ") R:" + remoteAddr + " -> L:" + localAddr);
                return false;
            } else {
                printLog("accept(" + listen.getChannelID() + ") R:" + remoteAddr + " -> L:" + localAddr);
                return true;
            }
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            return false;
        }
    }

    private void printLog(String msg) {
        if (this.context.getConfig().isPrintLog()) {
            try {
                logger.warn(msg);
            } catch (Exception ignored) {
            }
        }
    }

    protected UdpChannel newChannel(String remoteID, NetListen forListen, UdpAsyncChannel realChannel) throws IOException {
        NetMonitor monitor = new NetMonitor();
        UdpChannel channel = new UdpChannel(//
                realChannel.getChannelID(), //
                monitor,                    //
                forListen,                  //
                forListen.getInitializer(), //
                realChannel,                //
                this.context                //
        );

        channel.setAttribute(UdpIdentifier.class.getName(), new UdpIdentifier(remoteID));
        return channel;
    }
}