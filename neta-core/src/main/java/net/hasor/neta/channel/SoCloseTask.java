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

/**
 * closing the channel.
 * @version : 2023-10-09
 * @author 赵永春 (zyc@hasor.net)
 */
class SoCloseTask extends DefaultSoTask {
    private static final Logger        logger = Logger.getLogger(SoCloseTask.class);
    private final        long          channelID;
    private final        SoContextImpl context;
    private final        boolean       forceNow;
    //
    private              boolean       printLog;

    public SoCloseTask(long channelID, SoContextImpl context, boolean forceNow) {
        this.channelID = channelID;
        this.context = context;
        this.forceNow = forceNow;
        this.printLog = false;
    }

    @Override
    protected void doWork(int retryCnt) {
        SoChannel<?> channel = this.context.findChannel(this.channelID);
        if (channel == null) {
            finishTask();
            return;
        }

        if (channel.isClient() || channel.isServer()) {
            if (this.forceNow) {
                String msg = "channel(" + this.channelID + ") close now form local.";
                this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
                this.finishTask();
            } else {
                NetChannel netChannel = (NetChannel) channel;
                boolean needWaiting = !netChannel.wContext.isEmpty();

                // notifyRcv last message
                if (!this.printLog) {
                    if (needWaiting) {
                        logger.info("channel(" + this.channelID + ") safe close form local, waiting send finish.");
                    } else {
                        logger.info("channel(" + this.channelID + ") safe close form local.");
                    }
                    this.printLog = true;
                }

                // shutdownInput
                if (!netChannel.isShutdownInput()) {
                    netChannel.shutdownInput();
                }

                // wait send finish
                if (needWaiting) {
                    continueTask();
                    return;
                }

                if (netChannel.getSndHandlerStatus() != SoHandlerStatus.IDLE) {
                    continueTask();
                    return;
                }

                String msg = "channel(" + this.channelID + ") safe close form local.";
                this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
                netChannel.shutdownOutput();
                finishTask();
            }
        } else {
            String msg = "channel(" + this.channelID + ") close Listen.";
            this.context.syncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
            this.finishTask();
        }
    }
}