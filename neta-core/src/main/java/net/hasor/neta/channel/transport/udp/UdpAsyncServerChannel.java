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
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
/**
 * UDP server-side demultiplexer built on a single {@link DatagramChannel}.
 * <p>UDP has no real accept phase, so this server does not accept independent sockets the way TCP
 * does. Instead, it binds one datagram socket, receives datagrams through {@link UdpTransport},
 * and lazily creates logical {@link UdpChannel} instances keyed by remote {@code host:port}.
 * <p><b>Server flow:</b>
 * <pre>
 *   bind(initializer)
 *          ▼
 *   configListen(...) + create UdpNetListen
 *          ▼
 *   context.initChannel(listen)
 *          ▼
 *   transport.bind(listenAddr)
 *          ▼
 *   transport.startReceiveLoop(...)
 *          ▼
 *   onDatagram(listen, localAddr, channelMap, remoteAddr, data)
 *          ▼
 *   findOrCreateChannel(...)
 *    ┌─────────────┼──────────────────────────────┐
 *    ▼             ▼                              ▼
 * existing       listen suspend / reject         create logical channel
 *  reuse              return null                 │
 *    │                                            ├── new UdpAsyncChannel
 *    │                                            ├── new UdpChannel
 *    │                                            ├── initChannel(...)
 *    │                                            └── put into channelMap
 *    └───────────────────┬────────────────────────┘
 *                        ▼
 *              ByteBuffer -> ByteBuf
 *                        ▼
 *       notifyRcvChannelData(channelId, byteBuf)
 * </pre>
 * <p><b>Datagram routing model:</b> all logical peer channels share the same underlying socket.
 * The only per-peer differences are the framework-level channel identity and remote-address view.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see java.nio.channels.DatagramChannel
 */
public class UdpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger       logger = Logger.getLogger(UdpAsyncServerChannel.class);
    protected final long              channelId;
    protected final SoContextService  context;
    protected final InetSocketAddress listenAddr;
    protected final UdpSoConfig       soConfig;
    protected final ByteBufAllocator  bufAllocator;
    protected final UdpTransport      transport;

    /**
     * Create a UDP server asynchronous channel.
     * @param channelId the channel ID
     * @param channel the underlying DatagramChannel
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     * @throws IOException if an I/O error occurs during initialization
     */
    protected UdpAsyncServerChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.context = (SoContextService) context;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (UdpSoConfig) soConfig;

        this.bufAllocator = this.context.getByteBufAllocator();
        int rcvPacketSize = UdpSoConfigUtils.getRcvPacketSize(this.soConfig);
        this.transport = UdpTransport.wrap(channelId, channel, this.context, rcvPacketSize, this);
    }

    /**
     * Return the ID of the current listening channel.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the configuration used by the current listener.
     * @return the configuration object
     */
    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Determine whether the server transport is still open.
     * @return true if it is open
     */
    @Override
    public boolean isOpen() {
        return this.transport.isOpen();
    }

    /**
     * Close the server transport together with its receive loop.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("udpServerSide(" + this.getChannelId() + ") close.");
        }
        this.transport.close();
    }

    /**
     * Bind the listen address and start the UDP receive loop.
     * @param initializer the protocol initializer used for newly created logical channels
     * @return the listen handle
     * @throws IOException if an I/O error occurs during bind or initialization
     */
    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // Create the listen handle.
        UdpSoConfigUtils.configListen(this.soConfig, this.transport.getChannel());
        NetListen listen = new UdpNetListen( //
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // Initialize and start the receive flow.
        Map<String, UdpChannel> channelMap = new ConcurrentHashMap<>();
        SocketAddress localAddr;
        try {
            this.context.initChannel(listen, false);

            this.transport.bind(this.listenAddr);

            // Start the receive loop.
            localAddr = this.transport.getLocalAddress();
        } catch (Throwable e) {
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }

        final SocketAddress finalLocalAddr = localAddr;
        this.transport.startReceiveLoop((remoteAddr, data) -> this.onDatagram(listen, finalLocalAddr, channelMap, remoteAddr, data), listen::isClose, () -> {
            logger.info("rcv(" + this.channelId + ") close form local.");
            this.context.notifyChannelClose(this.channelId, false);
        }, (e) -> {
            SoRcvException err = new SoRcvException(e.getMessage(), e);
            this.context.notifyRcvChannelException(this.channelId, false, err);
        });
        return listen;
    }

    /**
     * Handle one received UDP datagram and route it to the corresponding logical channel.
     * @param listen the listen handle
     * @param localAddr the local address
     * @param channelMap the mapping from remote address to logical channel
     * @param remoteAddr the remote address of the current datagram
     * @param data the datagram payload
     * @throws IOException if an I/O error occurs during processing
     */
    protected void onDatagram(NetListen listen, SocketAddress localAddr, Map<String, UdpChannel> channelMap, SocketAddress remoteAddr, ByteBuffer data) throws IOException {
        InetSocketAddress inetRemoteAddr = (InetSocketAddress) remoteAddr;
        UdpChannel channel = this.findOrCreateChannel(listen, localAddr, inetRemoteAddr, this.transport.getChannel(), channelMap);
        if (channel == null) {
            return;
        }

        int remaining = data.remaining();
        ByteBuf byteBuf = this.bufAllocator.buffer(remaining);
        byteBuf.writeBuffer(data);
        byteBuf.markWriter();
        int readableBytes = byteBuf.readableBytes();

        channel.getNetMonitor().updateRcvCounter(readableBytes);
        if (logger.isDebugEnabled()) {
            logger.debug("rcv(" + this.channelId + ") the receive " + readableBytes + " bytes");
        }

        this.context.notifyRcvChannelData(channel.getChannelId(), byteBuf);
    }

    /**
     * Find or create the logical UDP channel associated with the given remote address.
     * @param listen the listen handle
     * @param localAddr the local address
     * @param remoteAddr the remote address
     * @param socket the underlying socket
     * @param channelMap the channel mapping table
     * @return the existing or newly created logical channel, or null when rejected
     * @throws SoConnectException if a connection-related exception occurs during channel creation
     */
    protected UdpChannel findOrCreateChannel(NetListen listen, SocketAddress localAddr, InetSocketAddress remoteAddr, DatagramChannel socket, Map<String, UdpChannel> channelMap) throws SoConnectException {
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

        // Create and initialize the logical channel.
        long newChannelId = this.context.nextID();
        try {
            channel = this.newChannel(remoteID, listen, new UdpAsyncChannel(newChannelId, socket, this.context, remoteAddr, this.soConfig));
            this.context.initChannel(channel, true);

            //
            channelMap.put(remoteID, channel);
            channel.onClose(c -> channelMap.remove(remoteID));
            return channel;
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            this.context.notifyConnectChannelException(newChannelId, true, ee);
            return null;
        }
    }

    /**
     * Determine whether creation of a logical channel for one remote address should be accepted.
     * @param listen the listen handle
     * @param localAddr the local address
     * @param remoteAddr the remote address
     * @return true if the remote endpoint is accepted, false otherwise
     */
    protected boolean acceptChannel(NetListen listen, SocketAddress localAddr, SocketAddress remoteAddr) {
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

    /**
     * Print a warning log when logging is enabled.
     * @param msg the log message
     */
    protected void printLog(String msg) {
        if (this.context.getConfig().isPrintLog()) {
            try {
                logger.warn(msg);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Create a new framework-level UDP channel for the specified remote endpoint.
     * @param remoteID the remote identifier
     * @param forListen the source listener
     * @param realChannel the underlying asynchronous channel
     * @return the newly created UdpChannel
     * @throws IOException if an I/O error occurs during creation
     */
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

        return channel;
    }
}