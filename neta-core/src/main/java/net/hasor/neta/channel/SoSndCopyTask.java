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

import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Copy the Queue data to the sndBuffer
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCopyTask extends DefaultSoTask {
    private static final Logger                    logger = Logger.getLogger(SoSndCopyTask.class);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        SoSndCompletionHandler    wHandler;
    private final        SoSndContext              wContext;
    private final        int                       taskIntervalMs;

    public SoSndCopyTask(long channelID, AsynchronousSocketChannel channel, SoSndCompletionHandler wHandler, SoSndContext wContext) {
        this.channelID = channelID;
        this.channel = channel;
        this.wHandler = wHandler;
        this.wContext = wContext;
        this.taskIntervalMs = this.wContext.getContext().getConfig().getRetryIntervalMs();
    }

    private void channelClose(Throwable e) {
        if (logger.isDebugEnabled()) {
            logger.debug("snd(" + this.channelID + ") channel is close, clean queue.");
        }

        SoSndData data = this.wContext.peekData();
        while (data != null) {
            data.failed(e);
            data = this.wContext.popData();
        }
    }

    @Override
    protected void doWork(int retryCnt) {
        SoContextImpl context = this.wContext.getContext();

        // channel is close
        if (context.isClose(this.channelID)) {
            ClosedChannelException e = new ClosedChannelException();
            channelClose(e);
            this.failedTask(e);
            return;
        }

        // require wHandler is ready
        if (this.wHandler.isSndWorking()) {
            if (logger.isDebugEnabled()) {
                logger.debug("snd(" + this.channelID + ") snd is working, wait next truns.");
            }

            this.delayTask(this.taskIntervalMs, TimeUnit.MILLISECONDS);
            return;
        }

        // merge SoSndData`s to sndBuffer
        List<SoSndData> afterFinish = new ArrayList<>();
        SoSndData data = this.wContext.peekData();
        if (data != null) {
            ByteBuf sndBuffer = this.wHandler.getSndBuffer();
            do {
                if (!sndBuffer.hasWritable()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("snd(" + this.channelID + ") snd is full, wait next truns.");
                    }
                    break;
                }

                int len = data.transferTo(sndBuffer);
                if (logger.isDebugEnabled()) {
                    logger.debug("snd(" + this.channelID + ") taskData transferTo sndBuffer " + len);
                }

                if (!data.hasReadable()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("snd(" + this.channelID + ") taskData be merged. " + data);
                    }

                    afterFinish.add(this.wContext.popData());
                    data = this.wContext.peekData();

                    if (data == null) {
                        break;
                    } else {
                        continue;
                    }
                }

                break;
            } while (true);
        }

        // start SoSndTask, send sndBuffer to socket
        long beginTime = this.wContext.getCreatedTime();
        SoSndTask task = new SoSndTask(this.channelID, beginTime, this.channel, this.wHandler, context, afterFinish);
        this.wContext.submitTask(task, this);

        if (data == null) {
            this.finishTask();
        } else {
            this.delayTask(this.taskIntervalMs, TimeUnit.MILLISECONDS);
        }
    }
}