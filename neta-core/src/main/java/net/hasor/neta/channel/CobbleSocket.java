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

/**
 * AIO TCP/IP,UDP/IP
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class CobbleSocket extends AbstractSocket {
    private static final Logger                   logger = Logger.getLogger(CobbleSocket.class);
    protected            AsynchronousChannelGroup channelGroup;

    public CobbleSocket(SoConfig config) {
        this.initTcp(config);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listenPort local port for listen
     * @param pipeStack Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(int listenPort, PipeStackFactory pipeStack) throws IOException {
        return this.listen(new InetSocketAddress("0.0.0.0", listenPort), pipeStack, null);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listenAddr local address for listen
     * @param listenPort local port for listen
     * @param pipeStack Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(String listenAddr, int listenPort, PipeStackFactory pipeStack) throws IOException {
        return this.listen(new InetSocketAddress(listenAddr, listenPort), pipeStack, null);
    }

    /**
     * using TCP/IP Listen on the port and bind Application layer network protocol to the accepted channels.
     *
     * @param listen local address:port for listen
     * @param pipeStack Application layer network protocol
     * @return A listener channel for accept incoming sockets
     */
    public synchronized NetListen listen(InetSocketAddress listen, PipeStackFactory pipeStack, NetListenOptions options) throws IOException {
        this.initChannelGroup();

        options = options == null ? NetListenOptions.DEFAULT : options;
        AsynchronousServerSocketChannel listenChannel = AsynchronousServerSocketChannel.open(this.channelGroup);
        SoConfigUtils.configListen(this.context.getConfig(), listenChannel);
        listenChannel.bind(listen, 0);

        long channelID = SoContextImpl.nextID();
        long createdTime = System.currentTimeMillis();
        NetListen netListen = new NetListen(channelID, createdTime, listen, listenChannel, pipeStack, this.context, options);
        listenChannel.accept(this.context, new SoAcceptCompletionHandler(netListen, listenChannel));

        this.context.openChannel(netListen);

        logger.info("listen at " + listen);
        return netListen;
    }

    /**
     * using TCP/IP connect to local port, and bind Application layer network protocol on this channel.
     * @param localPort local port
     * @param pipeStack Application layer network protocol
     */
    public Future<NetChannel> connect(int localPort, PipeStackFactory pipeStack) {
        return this.connect(new InetSocketAddress(localPort), pipeStack);
    }

    /**
     * using TCP/IP connect to local port, and bind Application layer network protocol on this channel.
     * @param remoteAddr local address
     * @param localPort local port
     * @param pipeStack Application layer network protocol
     */
    public Future<NetChannel> connect(String remoteAddr, int localPort, PipeStackFactory pipeStack) {
        return this.connect(new InetSocketAddress(remoteAddr, localPort), pipeStack);
    }

    /**
     * using TCP/IP connect to remote, and bind Application layer network protocol on this channel.
     * @param remoteAddr remoteAddr
     * @param pipeStack Application layer network protocol
     */
    public Future<NetChannel> connect(InetSocketAddress remoteAddr, PipeStackFactory pipeStack) {
        Future<NetChannel> future = new BasicFuture<>();
        try {
            this.initChannelGroup();

            AsynchronousSocketChannel clientChannel = AsynchronousSocketChannel.open(this.channelGroup);
            SoConfigUtils.configSocket(this.context.getConfig(), clientChannel);
            clientChannel.connect(remoteAddr, this.context, new SoConnectCompletionHandler(clientChannel, pipeStack, future));
            logger.info("connect to " + remoteAddr);
            return future;
        } catch (Exception e) {
            future.failed(e);
            return future;
        }
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
        if (this.channelGroup == null) {
            this.channelGroup = AsynchronousChannelGroup.withThreadPool(this.context.getIoExecutor());
        }
    }

    @Override
    protected void close0() {
        // close all channel
        logger.info("close all channel.");
        this.context.closeAll(false);

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
