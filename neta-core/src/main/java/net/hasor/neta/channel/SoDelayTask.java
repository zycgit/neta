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
import java.util.concurrent.TimeUnit;

/**
 * {@link DefaultSoTask} that re-schedules itself once after a delay, then finishes.
 * <p>On the first {@link DefaultSoTask#doWork(int)} call the task records a delay through
 * {@link DefaultSoTask#delayTask(int, TimeUnit)}. When the executor runs it again after that delay,
 * the task completes immediately. This makes it a lightweight building block for retry and backoff
 * loops submitted through {@link SoContextService#submitSoTask(DefaultSoTask, Object)}.
 * <p>The interval can be specified directly (milliseconds) or derived from
 * {@link NetConfig#getRetryIntervalMs()} via the context constructor.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see DefaultSoTask#delayTask
 */
public class SoDelayTask extends DefaultSoTask {
    private final int intervalMillis;

    /** Creates a delay task with a fixed interval in milliseconds. */
    public SoDelayTask(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** Creates a delay task whose interval is derived from {@link NetConfig#getRetryIntervalMs()}. */
    public SoDelayTask(SoContext context) {
        this.intervalMillis = Math.max(10, context.getConfig().getRetryIntervalMs());
    }

    @Override
    protected void doWork(int retryCnt) {
        if (retryCnt == 0 && this.intervalMillis > 0) {
            this.delayTask(this.intervalMillis, TimeUnit.MILLISECONDS);
        } else {
            this.finishTask();
        }
    }
}