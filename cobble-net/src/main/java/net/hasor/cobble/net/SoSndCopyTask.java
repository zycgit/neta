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
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.logging.Logger;

import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责 data Queue 到数据发送的分发
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoSndCopyTask extends AbstractSoTask {
    private static final Logger                    logger = Logger.getLogger(SoSndCopyTask.class);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        SoSndCompletionHandler    wHandler;
    private final        SoSndContext              wContext;

    public SoSndCopyTask(long channelID, AsynchronousSocketChannel channel, SoSndCompletionHandler wHandler, SoSndContext wContext) {
        this.channelID = channelID;
        this.channel = channel;
        this.wHandler = wHandler;
        this.wContext = wContext;
    }

    private void channelClose() {
        if (logger.isDebugEnabled()) {
            logger.debug("channel " + this.channelID + ", channel is close, clean queue.");
        }

        List<SoSndData> afterFinish = new ArrayList<>();
        SocketContext context = this.wContext.getContext();

        SoSndData data = this.wContext.peekData();
        long dataSize = 0;
        while (data != null) {
            dataSize += data.getDataSize();
            afterFinish.add(this.wContext.popData());
            data = this.wContext.peekData();
        }
        SoSndCleanTask task = new SoSndCleanTask(this.channelID, afterFinish, dataSize, new ClosedChannelException());
        context.submitSoTask(task, this);
    }

    @Override
    protected void doWork(boolean retry) {
        List<SoSndData> afterFinish = new ArrayList<>();
        SocketContext context = this.wContext.getContext();

        // channel is close
        if (this.wContext.getContext().isClose(this.channelID)) {
            channelClose();
            this.exitTask(new ClosedChannelException());
            return;
        }

        SoSndData data = this.wContext.peekData();
        while (data != null && !data.hasReadable()) {
            if (logger.isDebugEnabled()) {
                logger.debug("channel " + this.channelID + ", taskData skip -> " + data);
            }

            afterFinish.add(this.wContext.popData());
            data = this.wContext.peekData();
        }

        if (data != null) {
            if (this.wHandler.isSndWorking()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("channel " + this.channelID + ", snd is working, wait next truns.");
                }

                SoSndCleanTask task = new SoSndCleanTask(this.channelID, afterFinish);
                context.submitSoTask(task, this);

                this.delayTask();
                return;
            }

            ByteBuf sndBuffer = this.wHandler.getSndBuffer();
            // try merge multiple data to sndBuffer
            do {
                if (!sndBuffer.hasWritable()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("channel " + this.channelID + ", snd is full, wait next truns.");
                    }
                    break;
                }

                int len = data.transferTo(sndBuffer);
                if (logger.isDebugEnabled()) {
                    logger.debug("channel " + this.channelID + ", taskData transferTo sndBuffer " + len);
                }

                if (!data.hasReadable()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("channel " + this.channelID + ", taskData be merged." + data);
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

            //sndBuffer to socket
            long beginTime = this.wContext.getBeginTime();
            SoSndTask task = new SoSndTask(this.channelID, beginTime, this.channel, this.wHandler, context, afterFinish);
            context.submitSoTask(task, this);

            delayTask();
        } else {

            //            long beginTime = this.wContext.getBeginTime();
            //            this.channel.write(ZERO, context, new CompletionHandler<Integer, Object>() {
            //                @Override
            //                public void completed(Integer result, Object attachment) {
            //
            //                }
            //
            //                @Override
            //                public void failed(Throwable exc, Object attachment) {
            //
            //                }
            //            });

            context.submitSoTask(new SoSndCleanTask(this.channelID, afterFinish), afterFinish);
            finishTask();
        }
    }
}