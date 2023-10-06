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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;

import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Client Connect Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class ConnectCompletionHandler implements CompletionHandler<Void, SocketContext> {
    private static final Logger                    logger = Logger.getLogger(ConnectCompletionHandler.class);
    private final        TcpClient                 client;
    private final        AsynchronousSocketChannel channel;
    private final        Future<NetChannel>        future;

    public ConnectCompletionHandler(TcpClient client, AsynchronousSocketChannel channel, Future<NetChannel> future) {
        this.client = client;
        this.channel = channel;
        this.future = future;
    }

    @Override
    public void completed(Void result, SocketContext context) {
        long channelID = SocketContext.nextID();
        long beginTime = System.currentTimeMillis();
        SoRcvCompletionHandler rChannel = new SoRcvCompletionHandler(channelID, beginTime, this.channel, context);
        SoSndCompletionHandler wChannel = new SoSndCompletionHandler(channelID, beginTime, this.channel, context);
        NetChannel channel = new NetChannel(channelID, beginTime, this.channel, rChannel, wChannel, context);
        context.openChannel(channel);

        // continue accept
        try {
            logger.info("connectChannel " + channelID + " L:" + this.channel.getLocalAddress() + " -> R:" + this.channel.getRemoteAddress());
        } catch (Exception e) {
            logger.info("connectChannel " + channelID);
        }

        // read data
        context.submitSoTask(new SoRcvTask(channelID, beginTime, this.channel, rChannel, context), channel);
        this.future.completed(channel);
    }

    @Override
    public void failed(Throwable exc, SocketContext attachment) {
        this.future.failed(exc);
    }
}