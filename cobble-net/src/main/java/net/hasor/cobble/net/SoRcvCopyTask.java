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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * swapBuffer -> rcvBuffer
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoRcvCopyTask extends AbstractSoTask {
    private final SoContextImpl context;
    private final long          channelID;
    private final ByteBuffer    srcBuffer;
    private final ByteBuf       dstBuffer;
    private final int           taskIntervalMs;

    public SoRcvCopyTask(long channelID, SoContextImpl context, ByteBuffer srcBuffer, ByteBuf dstBuffer) {
        this.channelID = channelID;
        this.context = context;
        this.srcBuffer = srcBuffer;
        this.dstBuffer = dstBuffer;
        this.taskIntervalMs = context.getConfig().getRetryIntervalMs();
    }

    @Override
    protected void doWork(boolean retry) {
        if (this.context.isClose(this.channelID)) {
            this.exitTask(new ClosedChannelException());
            return;
        }

        if (this.srcBuffer.hasRemaining()) {
            if (this.dstBuffer.writableBytes() <= 0) {
                this.context.notifyChannelRcv(this.channelID);
                this.delayTask(this.taskIntervalMs);
            } else {
                // swapBuffer -> rcvBuffer
                try {
                    this.dstBuffer.waitLock(buf -> {
                        buf.write(this.srcBuffer);
                        buf.markWriter();
                    });
                } catch (IOException e) {
                    this.exitTask(e);
                    return;
                }

                this.context.notifyChannelRcv(this.channelID);
                this.continueTask();
            }
        } else {
            this.finishTask();
        }
    }
}
