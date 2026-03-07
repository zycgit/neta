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
 * Asynchronous task that gracefully closes a channel.
 * <h3>Safe-close sequence</h3>
 * <ol>
 *   <li>Wait for the write queue to drain (busy-spin via {@code continueTask()}).</li>
 *   <li>Fire a {@link SoCloseEvent} through the <em>SND</em> pipeline so that handlers
 *       can enqueue final farewell data (e.g. WebSocket Close frame, TLS close_notify) via
 *       {@link ProtoContext#sendData} — no {@code await()} needed, the data enters the queue
 *       synchronously.</li>
 *   <li>Wait again for the queue to drain (the farewell data written in step 2).</li>
 *   <li>Call {@code notifyChannelClose} to perform the actual teardown.</li>
 * </ol>
 * <p>In force mode, closes immediately without draining or firing the event.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-09
 */
class SoCloseTask extends DefaultSoTask {
    private static final Logger           logger = Logger.getLogger(SoCloseTask.class);
    private final        long             channelID;
    private final        SoContextService context;
    private final        boolean          forceNow;
    private              boolean          eventFired;

    public SoCloseTask(long channelID, SoContextService context, boolean forceNow) {
        this.channelID = channelID;
        this.context = context;
        this.forceNow = forceNow;
    }

    @Override
    protected void doWork(int retryCnt) {
        SoChannel<?> channel = this.context.findChannel(this.channelID);
        if (channel == null) {
            finishTask();
            return;
        }

        if (channel instanceof NetChannel) {
            if (this.forceNow) {
                this.context.notifyChannelClose(this.channelID, false);
                this.finishTask();
            } else {
                NetChannel netChannel = (NetChannel) channel;
                boolean needWaiting = !netChannel.wContext.isEmpty();

                if (this.context.getConfig().isPrintLog()) {
                    if (needWaiting) {
                        logger.info("channel(" + this.channelID + ") safe close form local, waiting send finish.");
                    } else {
                        logger.info("channel(" + this.channelID + ") safe close form local.");
                    }
                }

                // Phase 1 / Phase 2: wait for the write queue to drain.
                if (needWaiting) {
                    continueTask();
                    return;
                }

                // Phase 2: queue is empty. If we haven't fired the before-close event yet, do so now.
                if (!this.eventFired) {
                    this.eventFired = true;
                    SoUserEvent event = SoUserEventObject.of(netChannel, SoCloseEvent.class, SoCloseEvent.INSTANCE);
                    this.context.notifySndUserEvent(this.channelID, null, event);
                    // After notifySndUserEvent returns, flush pipline
                    netChannel.flushForClose();
                    continueTask(); // re-enter to drain any farewell data
                    return;
                }

                // Phase 3: event fired and queue is empty → perform actual close.
                this.context.notifyChannelClose(this.channelID, false);
                finishTask();
            }
        } else {
            this.context.notifyChannelClose(this.channelID, false);
            this.finishTask();
        }
    }
}