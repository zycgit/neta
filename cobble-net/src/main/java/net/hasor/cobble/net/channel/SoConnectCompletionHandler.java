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
package net.hasor.cobble.net.channel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Client Connect Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoConnectCompletionHandler implements CompletionHandler<Void, SoContextImpl> {
    private static final Logger                    logger = Logger.getLogger(SoConnectCompletionHandler.class);
    private final        SocketAddress             remoteAddress;
    private final        PipeLayerStack            pipeline;
    private final        AsynchronousSocketChannel channel;
    private final        Future<NetChannel>        future;

    public SoConnectCompletionHandler(AsynchronousSocketChannel channel, PipeLayerStack pipeline, Future<NetChannel> future) throws IOException {
        this.remoteAddress = channel.getRemoteAddress();
        this.channel = channel;
        this.pipeline = pipeline;
        this.future = future;
    }

    @Override
    public void completed(Void result, SoContextImpl context) {
        SocketAddress localAddr;
        SocketAddress remoteAddr;
        try {
            localAddr = this.channel.getLocalAddress();
            remoteAddr = this.channel.getRemoteAddress();
        } catch (Exception e) {
            IOUtils.closeQuietly(this.channel);
            logger.error("ERROR: Connect Failed " + e.getMessage(), e);
            this.failed(e, context);
            return;
        }

        long channelID = SoContextImpl.nextID();
        long createdTime = System.currentTimeMillis();
        SoResManager resManager = context.newSoResManager(channelID, this.remoteAddress);
        SoRcvCompletionHandler rChannel = new SoRcvCompletionHandler(channelID, createdTime, this.channel, context, resManager);
        SoSndCompletionHandler wChannel = new SoSndCompletionHandler(channelID, createdTime, this.channel, context, resManager);
        NetChannel channel = new NetChannel(channelID, createdTime, null, this.pipeline, localAddr, remoteAddr, this.channel, rChannel, wChannel, context, resManager);
        context.openChannel(channel);

        // continue accept
        try {
            logger.info("connect(" + channelID + ") L:" + this.channel.getLocalAddress() + " -> R:" + this.channel.getRemoteAddress());
        } catch (Exception e) {
            logger.info("connect(" + channelID + ")");
        }

        // async read data
        rChannel.resetSwapBuffer();
        this.channel.read(rChannel.getSwapBuffer(), context, rChannel);
        this.future.completed(channel);
    }

    @Override
    public void failed(Throwable exc, SoContextImpl attachment) {
        this.future.failed(exc);
    }
}