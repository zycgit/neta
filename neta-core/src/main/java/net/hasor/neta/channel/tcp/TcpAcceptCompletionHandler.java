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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.SoCloseException;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.channel.SoContextService;

import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Socket Accept Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class TcpAcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, SoContext> {
    private static final Logger                          logger = Logger.getLogger(TcpAcceptCompletionHandler.class);
    private final        NetListen                       forListen;
    private final        AsynchronousServerSocketChannel channel;

    TcpAcceptCompletionHandler(NetListen forListen, AsynchronousServerSocketChannel channel) {
        this.forListen = forListen;
        this.channel = channel;
    }

    @Override
    public void completed(AsynchronousSocketChannel result, SoContext attachment) {
        if (this.forListen.isClose()) {
            this.printLog("ERROR: AcceptFailed, listen is closed, ", result);
            IOUtils.closeQuietly(result);
            return;
        }

        // accept the next connection
        this.channel.accept(attachment, this);

        if (this.forListen.isSuspend()) {
            this.printLog("ERROR: AcceptFailed, listen is suspend, ", result);
            IOUtils.closeQuietly(result);
            return;
        }

        try {
            long channelId = ((SoContextService) attachment).nextID();
            attachment.initChannel(this.forListen, new TcpAsyncChannel(channelId, result));
        } catch (Throwable e) {
            IOUtils.closeQuietly(result);
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
        }
    }

    @Override
    public void failed(Throwable e, SoContext context) {
        if (e instanceof AsynchronousCloseException && this.forListen.isClose()) {
            return;
        }

        if (this.forListen.getContext().getConfig().isNetlog()) {
            if (e == SoCloseException.INSTANCE) {
                logger.info("ERROR: ListenFailed " + e.getMessage());
            } else {
                logger.error("ERROR: ListenFailed " + e.getMessage(), e);
            }
        }

        this.forListen.closeNow();
    }

    private void printLog(String msg, AsynchronousSocketChannel result) {
        if (this.forListen.getContext().getConfig().isNetlog()) {
            try {
                logger.warn(msg + result.getRemoteAddress());
            } catch (Exception ignored) {
            }
        }
    }
}