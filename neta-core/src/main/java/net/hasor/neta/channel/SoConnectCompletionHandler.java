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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;

import java.net.SocketAddress;
import java.nio.channels.CompletionHandler;

/**
 * Client Connect Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoConnectCompletionHandler implements CompletionHandler<Void, SoContextImpl> {
    private static final Logger             logger = Logger.getLogger(SoConnectCompletionHandler.class);
    private final        Pipeline<?>        pipeline;
    private final        NetChannel         channel;
    private final        SoAsyncChannel     asyncChannel;
    private final        Future<NetChannel> future;

    public SoConnectCompletionHandler(NetChannel channel, SoAsyncChannel asyncChannel, Future<NetChannel> future) {
        this.pipeline = channel.pipeline;
        this.channel = channel;
        this.asyncChannel = asyncChannel;
        this.future = future;
    }

    @Override
    public void completed(Void result, SoContextImpl context) {
        // when close then exit.
        if (context.isClose()) {
            logger.error("ERROR: Connect Failed, context is closed.");
            this.channel.close();
            return;
        }

        try {
            SocketAddress localAddress = this.asyncChannel.getLocalAddress();
            SocketAddress remoteAddress = this.asyncChannel.getRemoteAddress();
            logger.info("connected(" + channel.getChannelID() + ") L:" + localAddress + " -> R:" + remoteAddress);

            this.pipeline.onActive(this.channel.pipeCtx);
            this.channel.rHandler.read();
            this.future.completed(this.channel);
        } catch (Throwable e) {
            logger.error("ERROR: Connect finish, but onActive failed.");
            context.syncUnsafeCloseChannel(this.channel.getChannelID(), e.getMessage(), e);
            this.future.failed(e);
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        this.future.failed(e);
    }
}