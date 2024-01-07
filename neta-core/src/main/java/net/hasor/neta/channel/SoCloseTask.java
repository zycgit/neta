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
    private final boolean       forceNow;
    //
    private       boolean       notifyStatus;

    public SoCloseTask(long channelID, SoContextImpl context, boolean forceNow) {
        this.channelID = channelID;
        this.context = context;
        this.forceNow = forceNow;
        this.notifyStatus = false;
    }

    @Override
    protected void doWork(int retryCnt) {
        SoChannel<?> channel = this.context.findChannel(this.channelID);
        if (channel == null) {
            finishTask();
            return;
        }

        String msg = "channel(" + this.channelID + ") close form local.";
        if (channel.isClient() || channel.isServer()) {
            if (this.forceNow) {
                this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
                this.finishTask();
            } else {
                NetChannel netChannel = (NetChannel) channel;

                // shutdownInput
                if (!netChannel.isShutdownInput()) {
                    netChannel.shutdownInput();
                }

                // notifyRcv last message
                if (!this.notifyStatus) {
                    netChannel.notifyError(true, SoCloseException.INSTANCE);
                    this.notifyStatus = true;
                }

                // wait send finish
                if (!netChannel.wContext.isEmpty()) {
                    continueTask();
                    return;
                }

                //
                // 5. 还要考虑远程 buffer 可能满了导致永远无法关闭的问题
                // 5. -- pipline
                //            this.config.getSoKeepIntervalSec() 等待写入需要设置一个最大等待时间，否则可能无法关闭连接
                //            netChannel.channel.shutdownInput(); //当度被设置为 close 之后reader 会立刻触发 unsafeCloseChannel 需要处理
                //            netChannel.channel.shutdownOutput();
                continueTask();
            }
        } else {
            this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
            this.finishTask();
        }
    }
}