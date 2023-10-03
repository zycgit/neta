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

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.NotYetConnectedException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 负责 data Queue 到数据发送的分发
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoSndTask extends AbstractSoTask {
    private static final Logger                    logger = Logger.getLogger(SoSndTask.class);
    private final        long                      channelID;
    private final        long                      beginTime;
    private final        AsynchronousSocketChannel channel;
    private final        SoSndCompletionHandler    wHandler;
    private final        SocketContext             context;
    //
    private final        List<SoSndData>           afterFinish;

    public SoSndTask(long channelID, long beginTime, AsynchronousSocketChannel channel, SoSndCompletionHandler wHandler,//
            SocketContext context, List<SoSndData> afterFinish) {
        this.channelID = channelID;
        this.beginTime = beginTime;
        this.channel = channel;
        this.wHandler = wHandler;
        this.context = context;
        this.afterFinish = afterFinish;
    }

    @Override
    public void run() {
        try {
            Integer wTimeoutMs = this.context.getConfig().getSoWriteTimeoutMs();
            ByteBuffer swapBuf = this.wHandler.getSwapBuffer();
            ByteBuf sndBuf = this.wHandler.getSndBuffer();

            swapBuf.clear();
            sndBuf.read(swapBuf);
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
                long costTimeMs = System.currentTimeMillis() - this.beginTime;
                if (costTimeMs < this.context.getConnectTimeoutMs()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("snd(" + this.channelID + ") NotYetConnected, write try again later.");
                    }
                    this.delayTask();
                } else {
                    logger.warn("snd(" + this.channelID + ") Connection timeout. ");
                    this.exitTask(e);
                }
            } else {
                logger.error("rcv(" + this.channelID + ") " + e.getMessage(), e);
                this.context.closeChannel(this.channelID, false, e.getMessage());
                this.exitTask(e);
            }
        }
    }
}
