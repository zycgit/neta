/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.util.concurrent.TimeUnit;
/**
 * {@link DefaultSoTask} that delays itself once and then finishes.
 * <p>During the first execution of {@link DefaultSoTask#doWork(int)}, the task records a delay
 * through {@link DefaultSoTask#delayTask(int, TimeUnit)}. When the executor schedules it again
 * after that delay, the task finishes immediately.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see DefaultSoTask#delayTask
 */
@Deprecated
public class SoDelayTask extends DefaultSoTask {
    private final int intervalMillis;

    /** Create a delayed task with a fixed interval in milliseconds. */
    public SoDelayTask(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** Create a delayed task whose interval is taken from {@link NetConfig#getRetryIntervalMs()}. */
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
