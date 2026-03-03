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
import java.net.SocketAddress;
import java.nio.channels.CompletionHandler;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.AsyncChannel;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoConnectException;
import net.hasor.neta.channel.SoContextService;

/**
 * Completion handler for TCP client connections.
 * On success, initializes the channel, starts reading, and completes the future.
 * On failure, notifies the context and fails the future.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class TcpConnectCompletionHandler implements CompletionHandler<Void, SoContextService> {
    private static final Logger             logger = Logger.getLogger(TcpConnectCompletionHandler.class);
    private final        TcpChannel         channel;
    private final        AsyncChannel       asyncChannel;
    private final        Future<NetChannel> future;

    TcpConnectCompletionHandler(TcpChannel channel, AsyncChannel asyncChannel, Future<NetChannel> future) {
        this.channel = channel;
        this.asyncChannel = asyncChannel;
        this.future = future;
    }

    @Override
    public void completed(Void result, SoContextService context) {
        // when close then exit.
        if (context.isClose()) {
            logger.error("ERROR: Connect Failed, context is closed.");
            this.channel.close();
            return;
        }

        try {
            SocketAddress localAddress = this.asyncChannel.getLocalAddress();
            SocketAddress remoteAddress = this.asyncChannel.getRemoteAddress();
            logger.info("connected(" + this.channel.getChannelId() + ") L:" + localAddress + " -> R:" + remoteAddress);

            // init
            ((SoContextService) this.channel.getContext()).initChannel(this.channel, true);

            // start read
            if (!this.channel.isShutdownInput()) {
                this.channel.getReadHandler().read();
            }

            this.future.completed(this.channel);
        } catch (Throwable e) {
            logger.error("ERROR: Connect finish, but onActive failed.");
            this.failed(e, context);
        }
    }

    @Override
    public void failed(Throwable e, SoContextService context) {
        logger.error("ERROR: Connect failed, " + e.getMessage());
        SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
        context.notifyConnectChannelException(this.channel.getChannelId(), true, ee);
        this.future.failed(e);
    }
}