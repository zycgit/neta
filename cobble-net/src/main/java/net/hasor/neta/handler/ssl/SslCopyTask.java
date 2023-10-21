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
package net.hasor.neta.handler.ssl;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.DefaultSoTask;
import net.hasor.neta.channel.SoContext;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

/**
 * 异步数据写
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SslCopyTask extends DefaultSoTask {
    private final long       channelID;
    private final int        intervalMillis;
    private final SoContext  context;
    private final ByteBuffer input;
    private final ByteBuf    output;
    private       int        lazyCnt;

    public SslCopyTask(long channelID, SoContext context, ByteBuffer input, ByteBuf output) {
        this.channelID = channelID;
        this.intervalMillis = Math.max(10, context.getConfig().getRetryIntervalMs());
        this.context = context;
        this.input = input;
        this.output = output;
    }

    @Override
    protected void doWork(int retryCnt) {
        if (this.context.isClose(this.channelID)) {
            this.exitTask(new ClosedChannelException());
            return;
        }

        if (this.input.hasRemaining()) {
            this.lazyCnt++;
            int cnt = this.output.write(this.input);
            this.output.markWriter();

            if (cnt > 0) {
                this.lazyCnt = 0;
            }

            if (this.lazyCnt > 100) {
                this.delayTask(this.intervalMillis);
            } else {
                this.continueTask();
            }
        } else {
            this.finishTask();
        }
    }
}
