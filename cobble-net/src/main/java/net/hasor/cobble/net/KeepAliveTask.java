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
public class KeepAliveTask extends AbstractSoTask {
    private static final Logger        logger = Logger.getLogger(KeepAliveTask.class);
    private final        long          channelID;
    private final        NetChannel    channel;
    private final        SocketContext context;
    private final        long          intervalMs;
    private              long          nextSend;

    public KeepAliveTask(long channelID, long beginTime, NetChannel channel, SocketContext context) {
        this.channelID = channelID;
        this.channel = channel;
        this.context = context;

        Integer intervalMs = context.getConfig().getSoKeepAliveIntervalMs();
        if (intervalMs == null || intervalMs == 0) {
            this.intervalMs = 8000;
        } else {
            this.intervalMs = intervalMs;
        }

        this.nextSend = beginTime + this.intervalMs;
    }

    @Override
    public void run() {
        if (this.context.isClose(this.channelID)) {
            finishTask();
            return;
        }

        if (System.currentTimeMillis() < this.nextSend) {
            delayTask();
            return;
        }

        this.channel.sendEmpty();
        logger.debug("snd(" + this.channelID + ") send KeepAlive.");
        this.nextSend = System.currentTimeMillis() + this.intervalMs;
        delayTask();
    }
}
