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
import net.hasor.cobble.logging.Logger;

import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.TimeUnit;

/**
 * send swapBuffer to socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoRcvTask extends AbstractSoTask {
    private static final Logger                    logger = Logger.getLogger(SoRcvTask.class);
    private final        long                      channelID;
    private final        long                      beginTime;
    //
    private final        AsynchronousSocketChannel channel;
    private final        SoRcvCompletionHandler    rHandler;
    private final        SoContextImpl             context;

    public SoRcvTask(long channelID, long beginTime, AsynchronousSocketChannel channel, SoRcvCompletionHandler rHandler, SoContextImpl context) {
        this.channelID = channelID;
        this.beginTime = beginTime;

        this.channel = channel;
        this.rHandler = rHandler;
        this.context = context;
    }

    @Override
    protected void doWork(boolean retry) {
        if (this.context.isClose(this.channelID)) {
            this.exitTask(new ClosedChannelException());
            return;
        }

        try {
            this.rHandler.reset();
            Integer rTimeoutMs = this.context.getConfig().getSoReadTimeoutMs();
            if (rTimeoutMs != null && rTimeoutMs > 0) {
                this.channel.read(this.rHandler.getSwapBuffer(), rTimeoutMs, TimeUnit.MILLISECONDS, this.context, this.rHandler);
            } else {
                this.channel.read(this.rHandler.getSwapBuffer(), this.context, this.rHandler);
            }

            this.finishTask();
        } catch (Exception e) {
            if (e instanceof NotYetConnectedException) {
                long costTimeMs = System.currentTimeMillis() - this.beginTime;
                if (costTimeMs < this.context.getConnectTimeoutMs()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("rcv(" + this.channelID + ") NotYetConnected, read try again later.");
                    }
                    this.delayTask(this.context.getConfig().getRetryIntervalMs());
                } else {
                    logger.warn("rcv(" + this.channelID + ") Connection timeout.");
                    this.exitTask(e);
                }
            } else {
                logger.error("rcv(" + this.channelID + ") " + e.getMessage(), e);
                this.context.closeChannel(this.channelID, e.getMessage());
                this.exitTask(e);
            }
        }
    }
}