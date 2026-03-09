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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * TCP server transport built on {@link AsynchronousServerSocketChannel}.
 * <p>This class binds the listen socket, creates the framework-facing
 * {@link TcpNetListen}, and keeps the accept loop alive by re-arming
 * {@link TcpAcceptCompletionHandler} after every successful accept.
 * <p><b>Accept pipeline:</b>
 * <pre>
 *   AsynchronousServerSocketChannel.accept(...)
 *                 |
 *                 v
 *      TcpAcceptCompletionHandler
 *                 |
 *                 +--> configure accepted socket
 *                 +--> create TcpAsyncChannel
 *                 +--> create TcpChannel
 *                 +--> init pipeline
 *                 +--> start TcpRcvCompletionHandler.read()
 * </pre>
 * <p>The server transport itself is only responsible for the listen socket; each
 * accepted peer connection is handed off to its own {@link TcpAsyncChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger                          logger = Logger.getLogger(TcpAsyncServerChannel.class);
    private final        long                            channelId;
    private final        AsynchronousServerSocketChannel channel;
    private final        SoContextService                context;
    private final        InetSocketAddress               listenAddr;
    private final        TcpSoConfig                     soConfig;

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
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // create
        TcpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new TcpNetListen( //
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // init and start
        try {
            this.context.initChannel(listen, false);
            this.channel.bind(this.listenAddr, 0);
            this.channel.accept(context, new TcpAcceptCompletionHandler(listen, this.channel, this.soConfig));
        } catch (Throwable e) {
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }
        return listen;
    }

    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("tcpListen(" + this.getChannelId() + ") close.");
        }
        this.channel.close();
    }
}