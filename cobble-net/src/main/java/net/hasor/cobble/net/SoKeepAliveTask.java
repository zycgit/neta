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

/**
 * 处理 NotYetConnectedException 异常的延迟器
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoKeepAliveTask extends AbstractSoTask {
    private static final Logger        logger = Logger.getLogger(SoKeepAliveTask.class);
    private final        long          channelID;
    private final        NetChannel    channel;
    private final        SocketContext context;
    private final        long          intervalMs;

    public SoKeepAliveTask(long channelID, NetChannel channel, SocketContext context) {
        this.channelID = channelID;
        this.channel = channel;
        this.context = context;
        this.intervalMs = context.getSoKeepIntervalSec() * 1000L;
    }

    @Override
    protected void doWork(boolean retry) {
        if (this.context.isClose(this.channelID)) {
            finishTask();
            return;
        }

        long lastSndTime = this.channel.getLastSndTime();
        if ((lastSndTime + this.intervalMs) < System.currentTimeMillis()) {
            logger.debug("snd(" + this.channelID + ") send KeepAlive.");
            this.channel.sendEmpty();
        }

        delayTask(1000);
    }
}
