/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

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
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see java.nio.channels.DatagramChannel
 * @see java.util.concurrent.ExecutorService
 */
class UdpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger            logger = Logger.getLogger(UdpAsyncServerChannel.class);
    private final        long              channelId;
    private final        DatagramChannel   channel;
    private final        Selector          selector;
    private final        SoContextService  context;
    private final        InetSocketAddress listenAddr;
    private final        UdpSoConfig       soConfig;
    //
    private final        ByteBufAllocator  bufAllocator;
    private final        ByteBuffer        receiveBuffer;

    UdpAsyncServerChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.selector = Selector.open();
        this.context = (SoContextService) context;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (UdpSoConfig) soConfig;

        this.bufAllocator = this.context.getByteBufAllocator();
        this.receiveBuffer = this.bufAllocator.jvmBuffer(UdpSoConfigUtils.getRcvPacketSize(this.soConfig));
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
        return this.channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("udpServerSide(" + this.getChannelId() + ") close.");
        }
        IOUtils.closeQuietly(this.channel);
        IOUtils.closeQuietly(this.selector);
        ByteBufUtils.CLEANER.freeDirectBuffer(this.receiveBuffer);
    }

    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // create
        UdpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new UdpNetListen( //
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // init & start
        Map<String, UdpChannel> channelMap = new ConcurrentHashMap<>();
        SocketAddress localAddr;
        try {
            this.context.initChannel(listen, false);

            this.channel.bind(this.listenAddr);
            this.channel.configureBlocking(false);
            this.channel.register(this.selector, SelectionKey.OP_READ);

            // start receive loop
            localAddr = this.channel.getLocalAddress();
        } catch (Throwable e) {
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.receiveLoop(listen, localAddr, channelMap);
        });
        return listen;
    }

    private void receiveLoop(NetListen listen, SocketAddress localAddr, Map<String, UdpChannel> channelMap) {
        if (!this.channel.isOpen()) {
            logger.info("rcv(" + this.channelId + ") close form local.");
            this.context.notifyChannelClose(this.channelId, false);
            return;
        }

        try {
            if (this.selector.select(100) > 0) {
                this.receiveData(listen, localAddr, channelMap);
            }
        } catch (IOException e) {
            SoRcvException err = new SoRcvException(e.getMessage(), e);
            this.context.notifyRcvChannelException(this.channelId, false, err);
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            receiveLoop(listen, localAddr, channelMap);
        });
    }

    private void receiveData(NetListen listen, SocketAddress localAddr, Map<String, UdpChannel> channelMap) throws IOException {
        Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
        while (it.hasNext()) {
            // pull key
            SelectionKey key = it.next();
            it.remove();

            // process
            if (key.isReadable()) {
                try {
                    DatagramChannel channel = (DatagramChannel) key.channel();
                    this.readSocket(listen, localAddr, channelMap, channel);
                } catch (Throwable e) {
                    SoRcvException err = e instanceof SoRcvException ? (SoRcvException) e : new SoRcvException(e.getMessage(), e);
                    this.context.notifyRcvChannelException(this.channelId, false, err);
                    return;
                }
            }
        }
    }

    private void readSocket(NetListen listen, SocketAddress localAddr, Map<String, UdpChannel> channelMap, DatagramChannel socket) throws IOException {
        ((Buffer) this.receiveBuffer).clear();
        InetSocketAddress remoteAddr = (InetSocketAddress) socket.receive(this.receiveBuffer);
        UdpChannel channel = this.findOrCreateChannel(listen, localAddr, remoteAddr, socket, channelMap);
        if (channel == null) {
            return;
        }

        ByteBuf byteBuf = this.bufAllocator.buffer(this.receiveBuffer.position());
        ((Buffer) this.receiveBuffer).flip();
        byteBuf.writeBuffer(this.receiveBuffer);
        byteBuf.markWriter();
        int readableBytes = byteBuf.readableBytes();
        channel.getNetMonitor().updateRcvCounter(readableBytes);
        if (logger.isDebugEnabled()) {
            logger.debug("rcv(" + this.channelId + ") the receive " + readableBytes + " bytes");
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.context.notifyRcvChannelData(channel.getChannelId(), byteBuf);
        });
    }

    private UdpChannel findOrCreateChannel(NetListen listen, SocketAddress localAddr, InetSocketAddress remoteAddr, DatagramChannel socket, Map<String, UdpChannel> channelMap) throws SoConnectException {
        String remoteID = remoteAddr.getAddress().getHostAddress() + ":" + remoteAddr.getPort();
        UdpChannel channel = channelMap.get(remoteID);
        if (channel != null) {
            return channel;
        }

        if (listen.isSuspend()) {
            this.printLog("ERROR: AcceptFailed, listen is suspend.");
            return null;
        }

        if (!this.acceptChannel(listen, localAddr, remoteAddr)) {
            return null;
        }

        // create & init
        long newChannelId = this.context.nextID();
        try {
            channel = this.newChannel(remoteID, listen, new UdpAsyncChannel(newChannelId, socket, this.context, remoteAddr, this.soConfig));
            this.context.initChannel(channel, true);

            //
            channelMap.put(remoteID, channel);
            channel.onClose(c -> channelMap.remove(c.getAttribute(UdpIdentifier.class.getName()).toString()));
            return channel;
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(newChannelId, true, ee);
            return null;
        }
    }

    private boolean acceptChannel(NetListen listen, SocketAddress localAddr, SocketAddress remoteAddr) {
        try {
            if (!this.context.acceptChannel(remoteAddr)) {
                printLog("reject(" + listen.getChannelId() + ") R:" + remoteAddr + " -> L:" + localAddr);
                return false;
            } else {
                printLog("accept(" + listen.getChannelId() + ") R:" + remoteAddr + " -> L:" + localAddr);
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
                realChannel.getChannelId(), //
                monitor,                    //
                forListen,                  //
                forListen.getInitializer(), //
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