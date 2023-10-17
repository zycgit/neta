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
package net.hasor.cobble.net;
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
 * AIO TCP Server/Client
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class CobbleSocket extends AbstractSocket {
    private static final Logger                   logger = Logger.getLogger(CobbleSocket.class);
    protected            AsynchronousChannelGroup channelGroup;

    public CobbleSocket(SoConfig config) {
        this.initTcp(config);
    }

    /** 作为 server 监听本地端口，并接受链接 */
    public synchronized Future<NetListen> listen(int listenPort) {
        return this.listen(new InetSocketAddress(listenPort));
    }

    /** 作为 server 监听本地端口，并接受链接 */
    public synchronized Future<NetListen> listen(String listenAddr, int listenPort) {
        return this.listen(new InetSocketAddress(listenAddr, listenPort));
    }

    /** 作为 server 监听本地端口，并接受链接 */
    public synchronized Future<NetListen> listen(InetSocketAddress listen) {
        Future<NetListen> future = new BasicFuture<>();
        try {
            this.initChannelGroup();

            AsynchronousServerSocketChannel listenChannel = AsynchronousServerSocketChannel.open(this.channelGroup);
            SoConfigUtils.configListen(this.context.getConfig(), listenChannel);
            listenChannel.bind(listen, 0);

            long channelID = SoContextImpl.nextID();
            long createdTime = System.currentTimeMillis();
            NetListen netListen = new NetListen(channelID, createdTime, listen, listenChannel, this.context);
            listenChannel.accept(this.context, new SoAcceptCompletionHandler(netListen, listenChannel));

            this.context.openChannel(netListen);

            future.completed(netListen);
            logger.info("listen at " + listen);
            return future;
        } catch (Exception e) {
            future.failed(e);
            return future;
        }
    }

    /** 作为 client 向本机的特定端口发起链接请求 */
    public Future<NetChannel> connect(int localPort) {
        return this.connect(new InetSocketAddress(localPort));
    }

    /** 作为 client 发起链接请求 */
    public Future<NetChannel> connect(String remoteAddr, int localPort) {
        return this.connect(new InetSocketAddress(remoteAddr, localPort));
    }

    /** 作为 client 发起链接请求 */
    public Future<NetChannel> connect(InetSocketAddress remoteAddr) {
        Future<NetChannel> future = new BasicFuture<>();
        try {
            this.initChannelGroup();

            AsynchronousSocketChannel clientChannel = AsynchronousSocketChannel.open(this.channelGroup);
            SoConfigUtils.configSocket(this.context.getConfig(), clientChannel);
            clientChannel.connect(remoteAddr, this.context, new SoConnectCompletionHandler(clientChannel, future));
            logger.info("connect to " + remoteAddr);
            return future;
        } catch (Exception e) {
            future.failed(e);
            return future;
        }
    }

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
