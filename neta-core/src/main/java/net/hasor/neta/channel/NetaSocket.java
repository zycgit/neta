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
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AIO TCP/IP,UDP/IP
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetaSocket extends AbstractSocket {
    private static final Logger                   logger = Logger.getLogger(NetaSocket.class);
    protected            AsynchronousChannelGroup channelGroup;

    public NetaSocket(SoConfig config) {
        super(config);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listenPort local port for listen
     * @param pipeline Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(int listenPort, PipelineFactory pipeline) throws IOException {
        return this.listen(new InetSocketAddress("0.0.0.0", listenPort), pipeline, null);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listenAddr local address for listen
     * @param listenPort local port for listen
     * @param pipeline Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(String listenAddr, int listenPort, PipelineFactory pipeline) throws IOException {
        return this.listen(new InetSocketAddress(listenAddr, listenPort), pipeline, null);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listen local address:port for listen
     * @param pipeline Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(InetSocketAddress listen, PipelineFactory pipeline, NetListenOptions options) throws IOException {
        this.initChannelGroup();

        options = options == null ? NetListenOptions.DEFAULT : options;
        AsynchronousServerSocketChannel listenChannel = AsynchronousServerSocketChannel.open(this.channelGroup);
        SoConfigUtils.configListen(this.context.getConfig(), listenChannel);
        listenChannel.bind(listen, 0);

        long channelID = this.context.nextID();
        long createdTime = System.currentTimeMillis();
        NetListen netListen = new NetListen(channelID, createdTime, listen, listenChannel, pipeline, this.context, options);
        this.context.openChannel(netListen);

        listenChannel.accept(this.context, new SoAcceptCompletionHandler(netListen, listenChannel));
        logger.info("listen at " + listen);
        return netListen;
    }

    /**
     * using TCP/IP connect to local port, and bind Application layer network protocol on this channel.
     * @param localPort local port
     * @param pipeline Application layer network protocol
     */
    public Future<NetChannel> connect(int localPort, PipelineFactory pipeline) {
        return this.connect(new InetSocketAddress(localPort), pipeline);
    }

    /**
     * using TCP/IP connect to local port, and bind Application layer network protocol on this channel.
     * @param remoteAddr local address
     * @param localPort local port
     * @param pipeline Application layer network protocol
     */
    public Future<NetChannel> connect(String remoteAddr, int localPort, PipelineFactory pipeline) {
        return this.connect(new InetSocketAddress(remoteAddr, localPort), pipeline);
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param pipeline Application layer network protocol
     */
    public Future<NetChannel> connect(InetSocketAddress remoteAddr, PipelineFactory pipeline) {
        Future<NetChannel> future = new BasicFuture<>();
        try {
            this.initChannelGroup();

            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(this.channelGroup);
            SoConfigUtils.configSocket(this.context.getConfig(), channel);

            SoAsyncChannel asyncChannel = new SoAsyncChannel(channel, this.config);
            asyncChannel.connect(remoteAddr, this.context, new SoConnectCompletionHandler(asyncChannel, pipeline, future));
            logger.info("connect to " + remoteAddr);
            return future;
        } catch (Exception e) {
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

    //    /**
    //     * using UDP/IP on the port and bind Application layer network protocol to the channels.
    //     *
    //     * @param bindPort local port for bind
    //     * @param stackFactory Application layer network protocol
    //     * @return A channel for bind sockets
    //     */
    //    public synchronized NetChannel bind(int bindPort, PipeStackFactory stackFactory) throws IOException {
    //        return this.bind(new InetSocketAddress(bindPort), stackFactory);
    //    }
    //
    //    /**
    //     * using UDP/IP on the port and bind Application layer network protocol to the channels.
    //     *
    //     * @param bindAddr local address for listen
    //     * @param bindPort local port for bind
    //     * @param stackFactory Application layer network protocol
    //     * @return A channel for bind sockets
    //     */
    //    public synchronized NetChannel bind(String bindAddr, int bindPort, PipeStackFactory stackFactory) throws IOException {
    //        return this.bind(new InetSocketAddress(bindAddr, bindPort), stackFactory);
    //    }

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
