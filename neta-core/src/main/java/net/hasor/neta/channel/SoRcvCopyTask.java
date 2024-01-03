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
import net.hasor.neta.bytebuf.ByteBuf;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * asynchronous non-blocking copy receive data form swapBuffer {@link ByteBuffer} to rcvBuffer {@link ByteBuf}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoRcvCopyTask extends DefaultSoTask {
    private final SoContextImpl context;
    private final long          channelID;
    private final ByteBuffer    srcBuffer;
    private final ByteBuf       dstBuffer;

    public SoRcvCopyTask(long channelID, SoContextImpl context, ByteBuffer srcBuffer, ByteBuf dstBuffer) {
        this.channelID = channelID;
        this.context = context;
        this.srcBuffer = srcBuffer;
        this.dstBuffer = dstBuffer;
    }

    @Override
    protected void doWork(int retryCnt) {
        if (this.context.isClose(this.channelID)) {
            this.failedTask(SoCloseException.INSTANCE);
            return;
        }

        if (this.srcBuffer.hasRemaining()) {
            if (this.dstBuffer.writableBytes() <= 0) {
                this.context.notifyChannelRcv(this.channelID, 0, retryCnt);
                if (retryCnt > 5) {
                    this.delayTask(this.context.getConfig().getRetryIntervalMs(), TimeUnit.MILLISECONDS);
                } else {
                    this.delayTask(0, TimeUnit.MILLISECONDS);
                }
            } else {
                // swapBuffer -> rcvBuffer
                int len = this.dstBuffer.write(this.srcBuffer);
                this.dstBuffer.markWriter();

                this.context.notifyChannelRcv(this.channelID, len, retryCnt);
                this.continueTask();
            }
        } else {
            this.finishTask();
        }
    }
}