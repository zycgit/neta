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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * Socket Accept Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class AcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, SoContextImpl> {
    private static final Logger                          logger = Logger.getLogger(AcceptCompletionHandler.class);
    private final        TcpServer                       socketServer;
    private final        AsynchronousServerSocketChannel acceptChannel;

    public AcceptCompletionHandler(TcpServer socketServer, AsynchronousServerSocketChannel acceptChannel) {
        this.socketServer = socketServer;
        this.acceptChannel = acceptChannel;
    }

    @Override
    public void completed(AsynchronousSocketChannel result, SoContextImpl context) {
        // acceptChannel
        try {
            if (!context.acceptChannel(result.getRemoteAddress())) {
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
        long beginTime = System.currentTimeMillis();
        SoRcvCompletionHandler rChannel = new SoRcvCompletionHandler(channelID, beginTime, result, context);
        SoSndCompletionHandler wChannel = new SoSndCompletionHandler(channelID, beginTime, result, context);
        NetChannel channel = new NetChannel(channelID, beginTime, result, rChannel, wChannel, context);
        context.openChannel(channel);

        SoRcvTask task = new SoRcvTask(channelID, beginTime, result, rChannel, context);

        // read data
        context.submitSoTask(task, channel);

        // continue accept
        try {
            logger.info("acceptChannel " + channelID + " R:" + result.getRemoteAddress() + " -> L:" + result.getLocalAddress());
        } catch (Exception e) {
            logger.info("acceptChannel " + channelID);
        }
        this.acceptChannel.accept(context, this);
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        if (e instanceof AsynchronousCloseException) {
            try {
                this.socketServer.close0();
            } catch (Exception ee) {
                logger.debug("close SocketServer in AIO-AcceptThread failed, message: " + ee.getMessage());
            }
        } else {
            logger.error("ERROR: LISTEN Failed " + e.getMessage(), e);
        }
    }
}