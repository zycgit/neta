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
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * send swapBuffer to socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndTask extends DefaultSoTask {
    private static final Logger                 logger = Logger.getLogger(SoSndTask.class);
    private final        long                   channelID;
    private final        long                   createdTime;
    private final        SoAsyncChannel         channel;
    private final        SoSndCompletionHandler wHandler;
    private final        SoContextImpl          context;
    //
    private final        SoSndContext           wContext;

    public SoSndTask(long channelID, SoAsyncChannel channel, SoSndCompletionHandler wHandler, SoSndContext wContext) {
        this.channelID = channelID;
        this.createdTime = wContext.getCreatedTime();
        this.channel = channel;
        this.wHandler = wHandler;
        this.context = wContext.getContext();
        this.wContext = wContext;
    }

    @Override
    protected void doWork(int retryCnt) {
        if (this.context.isClose(this.channelID)) {
            this.failedTask(SoCloseException.INSTANCE);
            return;
        }
        if (this.channel.isShutdownOutput()) {
            this.failedTask(SoOutputCloseException.INSTANCE);
            return;
        }

        // when wHandler finish will wake up SoSndTask
        if (this.wHandler.getStatus() != SoHandlerStatus.IDLE) {
            this.finishTask();
            return;
        }

        // copy data from wQueue to sndBuf
        ByteBuf sndBuf = this.wHandler.getSndBuffer();
        List<SoSndData> afterFinish = fillByteBuf(sndBuf);
        if (!sndBuf.hasReadable() && this.wContext.peekData() == null) {
            if (!afterFinish.isEmpty()) {
                SoSndCleanTask cleanTask = new SoSndCleanTask(this.channelID, afterFinish, 0, null);
                this.context.submitSoTask(this.channelID, cleanTask, this);
            }
            this.finishTask();// nothing data to send.
            return;
        }

        // send data
        try {
            ByteBuffer swapBuf = this.wHandler.getSwapBuffer();

            this.wHandler.prepare(afterFinish);
            this.channel.write(swapBuf, this.context, this.wHandler);

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
                    SoConnectTimeoutException cause = SoUtils.newTimeout(true, this.channelID, this.context, e);

                    this.context.notifySndChannelError(this.channelID, cause);
                    this.context.syncUnsafeCloseChannel(this.channelID, cause.getMessage(), cause);
                    this.failedTask(e);
                }
            } else {
                String msg = "snd(" + this.channelID + ") " + e.getMessage();

                this.context.notifySndChannelError(this.channelID, e);
                this.context.syncUnsafeCloseChannel(this.channelID, msg, e);
                this.failedTask(e);
            }
        }
    }

    private List<SoSndData> fillByteBuf(ByteBuf sndBuf) {
        SoSndData data = this.wContext.peekData();
        if (data == null) {
            return Collections.emptyList();
        }

        // merge SoSndData`s to sndBuffer
        List<SoSndData> afterFinish = new ArrayList<>();
        do {
            int len = data.transferTo(sndBuf);
            if (logger.isDebugEnabled()) {
                logger.debug("snd(" + this.channelID + ") taskData transferTo sndBuffer " + len);
            }

            if (!data.hasReadable()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("snd(" + this.channelID + ") taskData be merged. " + data);
                }

                SoSndData popData = this.wContext.popData();
                if (popData != null) {
                    afterFinish.add(popData);
                }
                data = this.wContext.peekData();

                if (data == null) {
                    break;
                } else {
                    continue;
                }
            }

            break;
        } while (true);

        return afterFinish;
    }
}