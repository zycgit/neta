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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.tcp.TcpProvider;
import net.hasor.neta.channel.udp.UdpProvider;

import java.io.IOException;
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

    public NetManager() {
        super(new NetConfig());
    }

    public NetManager(NetConfig config) {
        super(config);
    }

    protected AsyncChannelProvider findProvider(String protocol) {
        if (StringUtils.equalsIgnoreCase(TcpProvider.NAME, protocol)) {
            return new TcpProvider();
        } else if (StringUtils.equalsIgnoreCase(UdpProvider.NAME, protocol)) {
            return new UdpProvider();
        } else {
            throw new UnsupportedOperationException("not support protocol : " + protocol);
        }
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     * @param listenAddr local address:port for listenAddr
     * @param initializer Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(SocketAddress listenAddr, ProtoInitializer initializer, SoConfig soConfig) throws Throwable {
        this.initChannelGroup();

        long channelID = this.context.nextID();
        AsyncChannelProvider provider = this.findProvider(soConfig.getProtocol());
        AsyncServerChannel socket = provider.createServerChannel(channelID, this.context, this.channelGroup, listenAddr, soConfig);
        NetListen listen = socket.bind(initializer);
        logger.info("listen at " + listenAddr);
        return listen;
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param initializer Application layer network protocol
     */
    public Future<NetChannel> connect(SocketAddress remoteAddr, ProtoInitializer initializer, SoConfig soConfig) {
        Future<NetChannel> future = new BasicFuture<>();
        AsyncChannel asyncChannel = null;

        try {
            this.initChannelGroup();
            long channelID = this.context.nextID();
            AsyncChannelProvider provider = this.findProvider(soConfig.getProtocol());
            asyncChannel = provider.createClientChannel(channelID, this.context, this.channelGroup, remoteAddr, soConfig);
            asyncChannel.connectTo(initializer, future);
            return future;
        } catch (Throwable e) {
            IOUtils.closeQuietly(asyncChannel);
            future.failed(e);
            return future;
        }
    }

    /** find SoChannel by id */
    public SoChannel<?> findChannel(long channelId) {
        return this.context.findChannel(channelId);
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
