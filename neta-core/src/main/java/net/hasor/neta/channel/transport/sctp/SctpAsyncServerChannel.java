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
package net.hasor.neta.channel.transport.sctp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpServerChannel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
/**
 * Selector-driven server-side transport implementation built on the JDK SCTP API.
 * <p>The server owns a listening {@link SctpServerChannel}, accepts native SCTP sockets,
 * registers them for read events, and lazily creates the corresponding framework-level
 * {@link SctpChannel} wrapper when data is first received.
 * <p><b>Runtime structure:</b>
 * <pre>
 *   SctpServerChannel (listening socket)
 *          |
 *          +--> accept native SctpChannel
 *                    |
 *                    +--> channelMap[native channel] = framework SctpChannel
 *                    +--> receive MessageInfo and payload
 *                    +--> wrap as SctpMessage
 *                    +--> notifyRcvChannelData(...)
 * </pre>
 * <p>The internal mapping uses the accepted native SCTP channel object as the key rather than a
 * remote address string or association ID. The selector loop reuses a single receive buffer and
 * copies data into a new {@link ByteBuf} before it enters the protocol pipeline.
 * <p>This transport depends on {@code com.sun.nio.sctp} and is therefore available only when both
 * the JDK and the operating system provide SCTP support.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see SctpAsyncChannel
 * @see SctpSoConfig
 */
class SctpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger     logger = Logger.getLogger(SctpAsyncServerChannel.class);
    private final long              channelId;
    private final SctpServerChannel channel;
    private final Selector          selector;
    private final SoContextService  context;
    private final InetSocketAddress listenAddr;
    private final SctpSoConfig      soConfig;
    //
    private final ByteBufAllocator bufAllocator;
    private final ByteBuffer       receiveBuffer;

    SctpAsyncServerChannel(long channelId, SctpServerChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.selector = Selector.open();
        this.context = (SoContextService) context;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (SctpSoConfig) soConfig;
        this.bufAllocator = this.context.getByteBufAllocator();
        this.receiveBuffer = this.bufAllocator.jvmBuffer(SctpSoConfigUtils.getRcvPacketSize(this.soConfig));
    }

    /**
     * Return the internal identifier of the listening channel.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the SCTP configuration used by the current listener.
     * @return the SCTP configuration object
     */
    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Determine whether the listening channel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Close the listening channel, selector, and the reused receive buffer.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("sctpListen(" + this.getChannelId() + ") close.");
        }
        this.channel.close();
        IOUtils.closeQuietly(this.selector);
        if (ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.receiveBuffer);
        }
    }

    /**
     * Bind the listening address and start the receive loop.
     * <p>This method first applies the listen-side configuration, then creates the framework-level
     * {@link NetListen} descriptor, and finally starts the accept and read loop.
     * @param initializer the protocol initializer for newly accepted connections
     * @return the corresponding listen handle
     * @throws IOException if an I/O error occurs during bind or initialization
     */
    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // create
        SctpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new SctpNetListen(//
                this.channelId,              //
                this.listenAddr,             //
                this.listenAddr.getPort(),   //
                this,                        //
                initializer,                 //
                this.context,                //
                this.soConfig);

        // init & start
        Map<Object, SctpChannel> channelMap = new ConcurrentHashMap<>();
        Set<SocketAddress> localAddr;
        try {
            this.context.initChannel(listen, false);
            this.channel.bind(this.listenAddr);
            this.channel.configureBlocking(false);
            this.channel.register(this.selector, SelectionKey.OP_ACCEPT);

            // start receive loop
            localAddr = this.channel.getAllLocalAddresses();
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

    private void receiveLoop(NetListen listen, Set<SocketAddress> localAddr, Map<Object, SctpChannel> channelMap) {
        if (!this.channel.isOpen()) {
            logger.info("accept(" + this.channelId + ") close form local.");
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
            this.receiveLoop(listen, localAddr, channelMap);
        });
    }

    private void receiveData(NetListen listen, Set<SocketAddress> localAddr, Map<Object, SctpChannel> channelMap) {
        Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();

            try {
                if (key.isAcceptable()) {
                    SctpServerChannel server = (SctpServerChannel) key.channel();
                    com.sun.nio.sctp.SctpChannel client = server.accept();
                    if (client != null) {
                        client.configureBlocking(false);
                        client.register(this.selector, SelectionKey.OP_READ);
                    }
                } else if (key.isReadable()) {
                    com.sun.nio.sctp.SctpChannel channel = (com.sun.nio.sctp.SctpChannel) key.channel();
                    this.readSocket(listen, localAddr, channelMap, channel);
                }
            } catch (Throwable e) {
                if (this.context.getConfig().isPrintLog()) {
                    if (e instanceof SoCloseException) {
                        logger.info("ERROR: AcceptOrReadFailed " + e.getMessage());
                    } else {
                        logger.error("ERROR: AcceptOrReadFailed " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private void readSocket(NetListen listen, Set<SocketAddress> localAddr, Map<Object, SctpChannel> channelMap, com.sun.nio.sctp.SctpChannel socket) throws IOException {
        SctpChannel channel = this.findOrCreateChannel(listen, localAddr, socket, channelMap);
        if (channel == null) {
            IOUtils.closeQuietly(socket);
            return;
        }

        this.receiveBuffer.clear();
        MessageInfo info = socket.receive(this.receiveBuffer, this.context, channel.notificationHandler());
        if (info == null) {
            return;
        }

        this.fireRcvData(channel, info, this.receiveBuffer);
        if (!info.isComplete()) {
            while (true) {
                this.receiveBuffer.clear();
                info = socket.receive(this.receiveBuffer, this.context, channel.notificationHandler());
                if (info == null) {
                    break;
                }
                this.fireRcvData(channel, info, this.receiveBuffer);
                if (info.isComplete()) {
                    break;
                }
            }
        }
    }

    private void fireRcvData(SctpChannel socket, MessageInfo info, ByteBuffer receiveBuffer) {
        ByteBuf byteBuf = this.bufAllocator.buffer(receiveBuffer.position());
        receiveBuffer.flip();
        byteBuf.writeBuffer(receiveBuffer);
        byteBuf.markWriter();
        int readableBytes = byteBuf.readableBytes();
        socket.getNetMonitor().updateRcvCounter(readableBytes);
        if (logger.isDebugEnabled()) {
            logger.debug("rcv(" + this.channelId + ") the receive " + readableBytes + " bytes");
        }

        final SctpMessage message = SctpMessage.of(info, byteBuf);
        this.context.notifyRcvChannelData(socket.getChannelId(), message);
    }

    private SctpChannel findOrCreateChannel(NetListen listen, Set<SocketAddress> localAddresses, com.sun.nio.sctp.SctpChannel sctpChannel, Map<Object, SctpChannel> channelMap) throws IOException {
        Set<SocketAddress> remoteAddresses = sctpChannel.getRemoteAddresses();
        SctpChannel channel = channelMap.get(sctpChannel);
        if (channel != null) {
            return channel;
        }

        if (listen.isSuspend()) {
            this.printLog("ERROR: AcceptFailed, listen is suspend.");
            return null;
        }

        SctpSocketAddress localAddr = new SctpSocketAddress(sctpChannel.association(), localAddresses);
        SctpSocketAddress remoteAddr = new SctpSocketAddress(sctpChannel.association(), remoteAddresses);
        if (!this.acceptChannel(listen, localAddr, remoteAddr)) {
            return null;
        }

        // create & init
        long newChannelId = this.context.nextID();
        try {
            channel = this.newChannel(listen, new SctpAsyncChannel(newChannelId, sctpChannel, localAddr, remoteAddr, this.context, this.soConfig));
            this.context.initChannel(channel, true);

            //
            final com.sun.nio.sctp.SctpChannel sctpKey = sctpChannel;
            channelMap.put(sctpKey, channel);
            channel.onClose(c -> channelMap.remove(sctpKey));
            return channel;
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(newChannelId, true, ee);
            return null;
        }
    }

    private boolean acceptChannel(NetListen listen, SocketAddress localAddr, SctpSocketAddress remoteAddr) {
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

    /**
     * Create the framework-level channel object for an accepted native SCTP channel.
     * @param forListen the source listener
     * @param realChannel the underlying asynchronous channel adapter
     * @return the newly created framework channel
     * @throws IOException if an I/O error occurs during creation
     */
    protected SctpChannel newChannel(NetListen forListen, SctpAsyncChannel realChannel) throws IOException {
        return new SctpChannel(             //
                realChannel.getChannelId(), //
                new NetMonitor(),           //
                forListen,                  //
                forListen.getInitializer(), //
                realChannel,                //
                this.context                //
        );
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}