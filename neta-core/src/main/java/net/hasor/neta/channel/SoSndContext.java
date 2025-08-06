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
import net.hasor.cobble.concurrent.future.Future;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * send Data context
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoSndContext {
    private final long             channelID;
    private final long             createdTime;
    private final SoContextService context;
    private final Queue<SoSndData> wQueue;

    public SoSndContext(long channelID, long createdTime, SoContextService context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.context = context;
        this.wQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * channel accept time or connect time
     */
    public long getCreatedTime() {
        return this.createdTime;
    }

    /**
     * poll data form wQueue
     */
    public SoSndData popData() {
        return this.wQueue.poll();
    }

    /** purge hasn't sent data */
    public void purge(Throwable e) {
        SoSndData data;
        do {
            data = this.wQueue.poll();
            if (data != null) {
                try {
                    data.failed(e);
                } catch (Exception ignored) {

                }
            }
        } while (data != null);
    }

    public boolean hasData() {
        return !this.wQueue.isEmpty();
    }

    /**
     * peek data form wQueue
     */
    public SoSndData peekData() {
        return this.wQueue.peek();
    }

    /** offer data to send */
    public void offer(SoSndData sndData) {
        this.wQueue.offer(sndData);
    }

    /** test wQueue is empty */
    public boolean isEmpty() {
        return this.wQueue.isEmpty();
    }

    /**
     * return {@link SoContextService}
     */
    public SoContextService getContext() {
        return this.context;
    }

    /**
     * submit async task to run.
     */
    public Future<?> submitTask(DefaultSoTask task, Object context) {
        return this.context.submitSoTask(this.channelID, task, context);
    }

}