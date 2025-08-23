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
package net.hasor.neta.channel.tcp;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;

/**
 * TCP implementation of asynchronous server channel.
 * Provides TCP-specific implementation for accepting incoming connections asynchronously.
 * Wraps Java NIO's AsynchronousServerSocketChannel for actual network operations.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncServerChannel implements AsyncServerChannel {
    private final long                            channelId;
    private final AsynchronousServerSocketChannel channel;
    private final SoContextService                context;
    private final InetSocketAddress               listenAddr;
    private final TcpSoConfig                     soConfig;

    TcpAsyncServerChannel(long channelId, AsynchronousServerSocketChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.channel = channel;
        this.context = (SoContextService) context;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (TcpSoConfig) soConfig;
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
    public NetListen bind(ProtoInitializer initializer) throws Throwable {
        // create
        TcpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new NetListen( //
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // init
        this.context.initChannel(listen, false);

        // start
        this.channel.bind(this.listenAddr, 0);
        this.channel.accept(context, new TcpAcceptCompletionHandler(listen, this.channel, this.soConfig));
        return listen;
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}