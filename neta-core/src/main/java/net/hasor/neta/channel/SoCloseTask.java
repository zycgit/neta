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
/**
 * closing the channel.
 * @version : 2023-10-09
 * @author 赵永春 (zyc@hasor.net)
 */
class SoCloseTask extends DefaultSoTask {
    private final long          channelID;
    private final SoContextImpl context;
    private final boolean       force;

    public SoCloseTask(long channelID, SoContextImpl context, boolean force) {
        this.channelID = channelID;
        this.context = context;
        this.force = force;
    }

    @Override
    protected void doWork(int retryCnt) {
        String msg = "channel(" + channelID + ") close form local.";
        if (this.force) {
            this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
        } else {
            this.context.safeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
        }
        this.finishTask();
    }
}