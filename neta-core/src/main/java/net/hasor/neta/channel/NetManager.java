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
package net.hasor.neta.channel;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.tcp.TcpAsyncChannel;
import net.hasor.neta.channel.tcp.TcpAsyncServerChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AIO TCP/IP
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetManager extends AbstractNetManager {
    private static final Logger                   logger = Logger.getLogger(NetManager.class);
    protected            AsynchronousChannelGroup channelGroup;

    public NetManager(SoConfig config) {
        super(config);
    }

    //    /**
    //     * using UDP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
    //     * @param listen local address:port for listen
    //     * @param initializer Application layer network protocol
    //     * @return A listener channel for accept incoming sockets
    //     */
    //    public synchronized NetListen bind(InetSocketAddress listen, ProtoInitializer initializer, NetListenOptions options) throws IOException {
    //        this.initChannelGroup();
    //
    //        options = options == null ? new NetListenOptions() : options;
    //        AsyncServerChannel channel = UdpAsyncServerChannel.createChannel(this.config, this.channelGroup);
    //
    //        long channelID = this.context.nextID();
    //        long createdTime = System.currentTimeMillis();
    //        NetListen netListen = new NetListen(channelID, createdTime, listen, channel, initializer, this.context, options);
    //        this.context.openChannel(netListen, listen);
    //
    //        channel.bind(netListen, this.context);
    //        logger.info("bind at " + listen);
    //        return netListen;
    //    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     * @param listenAddr local address for listen
     * @param listenPort local port for listen
     * @param initializer Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(String listenAddr, int listenPort, ProtoInitializer initializer) throws IOException {
        return this.listen(new InetSocketAddress(listenAddr, listenPort), initializer, null);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     * @param listenAddr local address:port for listenAddr
     * @param initializer Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(InetSocketAddress listenAddr, ProtoInitializer initializer, NetListenOptions options) throws IOException {
        this.initChannelGroup();

        options = options == null ? new NetListenOptions() : options;
        AsyncServerChannel socket = TcpAsyncServerChannel.openChannel(this.context.nextID(), this.config, this.channelGroup);

        long channelID = socket.getChannelID();
        long createdTime = System.currentTimeMillis();
        NetListen listen = new NetListen(channelID, createdTime, listenAddr, socket, initializer, this.context, options);
        this.context.openChannel(listen, listenAddr);

        socket.bind(listen, this.context);
        logger.info("listenAddr at " + listenAddr);
        return listen;
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remote address
     * @param remotePort remote port
     * @param initializer Application layer network protocol
     */
    public Future<NetChannel> connect(String remoteAddr, int remotePort, ProtoInitializer initializer) {
        return this.connect(new InetSocketAddress(remoteAddr, remotePort), initializer);
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param initializer Application layer network protocol
     */
    public Future<NetChannel> connect(InetSocketAddress remoteAddr, ProtoInitializer initializer) {
        Future<NetChannel> future = new BasicFuture<>();
        SoAsyncChannel asyncChannel = null;
        NetChannel channel;

        try {
            // aio Channel
            this.initChannelGroup();
            long channelID = this.context.nextID();
            long createdTime = System.currentTimeMillis();
            AsyncChannel aioChannel = TcpAsyncChannel.openChannel(channelID, this.config, this.channelGroup);
            asyncChannel = new SoAsyncChannel(aioChannel, this.context.getByteBufAllocator(), this.config);

            // init NetChannel
            SoSndContext wContext = new SoSndContext(channelID, createdTime, this.context);
            SoRcvCompletionHandler rHandler = new SoRcvCompletionHandler(channelID, createdTime, asyncChannel, this.context);
            SoSndCompletionHandler wHandler = new SoSndCompletionHandler(channelID, createdTime, asyncChannel, wContext);

            SocketAddress localAddr = asyncChannel.getLocalAddress();
            channel = new NetChannel(channelID, createdTime, null, localAddr, remoteAddr, asyncChannel, rHandler, wHandler, wContext);

            // init ProtoStack
            ProtoContextService protoCtx = new ProtoContextService(channel, this.context);
            channel.initChannel(protoCtx, initializer.config(protoCtx));
        } catch (Throwable e) {
            IOUtils.closeQuietly(asyncChannel);
            future.failed(e);
            return future;
        }

        try {
            // init ProtoStack
            this.context.openChannel(channel, remoteAddr);
            channel.protoStack.onInit(channel.protoCtx);

            // connect to
            asyncChannel.connect(remoteAddr, this.context, new SoConnectCompletionHandler(channel, asyncChannel, future));
            logger.info("initialize connect(" + channel.getChannelID() + ") to " + remoteAddr);
            return future;
        } catch (Throwable e) {
            this.context.syncUnsafeCloseChannel(channel.getChannelID(), e.getMessage(), e);
            future.failed(e);
            return future;
        }
    }

    /** find SoChannel by id */
    public SoChannel<?> findChannel(long channelID) {
        return this.context.findChannel(channelID);
    }

    /** find NetListen by listenPort */
    public NetListen findListen(int port) {
        AtomicReference<NetListen> found = new AtomicReference<>();
        this.context.foreachListen(netListen -> {
            if (netListen.getListenPort() == port) {
                found.set(netListen);
            }
        });

        return found.get();
    }

    protected void initChannelGroup() throws IOException {
        if (this.shutdown.get()) {
            throw new IllegalStateException("service is shutdown.");
        }

        if (this.channelGroup == null) {
            this.channelGroup = AsynchronousChannelGroup.withThreadPool(this.context.getIoExecutor());
        }
    }

    @Override
    protected void shutdown0(boolean now) {
        // close all channel
        if (now) {
            logger.info("close all channel for now.");
        } else {
            logger.info("close all channel.");
        }
        this.context.closeAll(now);

        // waiting close
        long t = System.currentTimeMillis();
        this.channelGroup.shutdown();
        while (!this.channelGroup.isTerminated()) {
            long cost = System.currentTimeMillis() - t;
            if (cost > 3000) {
                t = System.currentTimeMillis();
                logger.info("close channelGroup waiting...");
            }
            ThreadUtils.sleep(50);
        }
        logger.info("close channelGroup done.");
    }
}
