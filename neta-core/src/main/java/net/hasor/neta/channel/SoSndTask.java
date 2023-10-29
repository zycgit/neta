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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * send swapBuffer to socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndTask extends DefaultSoTask {
    private static final Logger                    logger = Logger.getLogger(SoSndTask.class);
    private final        long                      channelID;
    private final        long                      createdTime;
    private final        AsynchronousSocketChannel channel;
    private final        SoSndCompletionHandler    wHandler;
    private final        SoContextImpl             context;
    //
    private final        List<SoSndData>           afterFinish;

    public SoSndTask(long channelID, long createdTime, AsynchronousSocketChannel channel, SoSndCompletionHandler wHandler,//
            SoContextImpl context, List<SoSndData> afterFinish) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.channel = channel;
        this.wHandler = wHandler;
        this.context = context;
        this.afterFinish = afterFinish;
    }

    @Override
    protected void doWork(int retryCnt) {
        if (this.context.isClose(this.channelID)) {
            this.failedTask(new ClosedChannelException());
            return;
        }

        try {
            Integer wTimeoutMs = this.context.getConfig().getSoWriteTimeoutMs();
            ByteBuffer swapBuf = this.wHandler.getSwapBuffer();
            ByteBuf sndBuf = this.wHandler.getSndBuffer();

            swapBuf.clear();
            sndBuf.read(swapBuf);
            sndBuf.markReader();
            swapBuf.flip();

            this.wHandler.prepareWrite(this.afterFinish);
            if (wTimeoutMs != null && wTimeoutMs > 0) {
                this.channel.write(swapBuf, wTimeoutMs, TimeUnit.MILLISECONDS, this.context, this.wHandler);
            } else {
                this.channel.write(swapBuf, this.context, this.wHandler);
            }

            this.finishTask();
        } catch (Exception e) {
            if (e instanceof NotYetConnectedException) {
                long costTimeMs = System.currentTimeMillis() - this.createdTime;
                if (costTimeMs < this.context.getConnectTimeoutMs()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("snd(" + this.channelID + ") NotYetConnected, write try again later.");
                    }
                    this.delayTask(this.context.getConfig().getRetryIntervalMs(), TimeUnit.MILLISECONDS);
                } else {
                    logger.warn("snd(" + this.channelID + ") Connection timeout. ");
                    this.failedTask(e);
                }
            } else {
                logger.error("snd(" + this.channelID + ") " + e.getMessage(), e);
                this.context.closeChannel(this.channelID, e.getMessage());
                this.failedTask(e);
            }
        }
    }
}
