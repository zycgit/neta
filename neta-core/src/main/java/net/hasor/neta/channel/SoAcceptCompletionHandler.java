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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Socket Accept Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoAcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, SoContextImpl> {
    private static final Logger                          logger = Logger.getLogger(SoAcceptCompletionHandler.class);
    private final        NetListen                       forListen;
    private final        AsynchronousServerSocketChannel acceptChannel;

    public SoAcceptCompletionHandler(NetListen forListen, AsynchronousServerSocketChannel acceptChannel) {
        this.forListen = forListen;
        this.acceptChannel = acceptChannel;
    }

    @Override
    public void completed(AsynchronousSocketChannel result, SoContextImpl context) {
        // accept the next connection
        this.acceptChannel.accept(context, this);

        // listen is suspend
        if (this.forListen.isSuspend()) {
            IOUtils.closeQuietly(result);
            return;
        }

        // acceptChannel
        SocketAddress localAddr;
        SocketAddress remoteAddr;
        try {
            localAddr = result.getLocalAddress();
            remoteAddr = result.getRemoteAddress();
            if (!context.acceptChannel(remoteAddr)) {
                IOUtils.closeQuietly(result);
                return;
            }
        } catch (Exception e) {
            IOUtils.closeQuietly(result);
            logger.error("ERROR: Accept Failed " + e.getMessage(), e);
            return;
        }

        // config new socket
        try {
            SoConfigUtils.configSocket(context.getConfig(), result);
        } catch (IOException e) {
            IOUtils.closeQuietly(result);
            logger.error("ERROR: Accept Failed " + e.getMessage(), e);
            return;
        }

        // openChannel
        long channelID = SoContextImpl.nextID();
        long createdTime = System.currentTimeMillis();
        context.specialConfig(channelID, remoteAddr);

        SoRcvCompletionHandler rChannel = new SoRcvCompletionHandler(channelID, createdTime, result, context);
        SoSndCompletionHandler wChannel = new SoSndCompletionHandler(channelID, createdTime, result, context);
        NetChannel channel = new NetChannel(channelID, createdTime, this.forListen, localAddr, remoteAddr, result, rChannel, wChannel, context);

        // init and pipe
        try {
            PipeContextImpl pipeCtx = new PipeContextImpl(channel, context);
            PipeStack<?, ?> pipeStack = this.forListen.getStackFactory().create(pipeCtx);
            channel.initPipe(pipeCtx, pipeStack);
            context.openChannel(channel);
            logger.info("accept(" + channelID + ") R:" + remoteAddr + " -> L:" + localAddr);
        } catch (Throwable e) {
            IOUtils.closeQuietly(result);
            logger.error("ERROR: Accept Failed " + e.getMessage(), e);
            return;
        }

        this.forListen.notifyAccept(channel);

        // async read data
        rChannel.resetSwapBuffer();
        result.read(rChannel.getSwapBuffer(), context, rChannel);
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        String msg = "ERROR: Listen Failed " + e.getMessage();

        context.notifyChannelError(this.forListen.getChannelID(), e);
        context.unsafeCloseChannel(this.forListen.getChannelID(), msg, e);
    }
}