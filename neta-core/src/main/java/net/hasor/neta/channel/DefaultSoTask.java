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
 * Base class for retryable event-loop tasks.
 * A task signals its result by calling {@link #finishTask()}, {@link #continueTask()},
 * {@link #delayTask} or {@link #failedTask} at the end of {@link #doWork}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class DefaultSoTask implements Runnable {
    private SoTaskStatus status = SoTaskStatus.Finish;
    private int          delayTime;
    private TimeUnit     delayUnit;
    private Exception    cause;
    private int          retryCnt;

    /** Returns the failure cause if the last {@code doWork} call ended with {@link #failedTask}. */
    public Throwable getCause() {
        return this.cause;
    }

    /** Returns the current execution status of this task. */
    public SoTaskStatus getStatus() {
        return this.status;
    }

    /** Returns the delay duration set by the last {@link #delayTask} call. */
    public int getDelayTime() {
        return this.delayTime;
    }

    /** Returns the time unit for the delay set by the last {@link #delayTask} call. */
    public TimeUnit getDelayUnit() {
        return this.delayUnit;
    }

    protected void delayTask(int delay, TimeUnit unit) {
        this.delayTime = delay;
        this.delayUnit = unit;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    protected void continueTask() {
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    protected void finishTask() {
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Finish;
    }

    protected void failedTask(Exception e) {
        this.delayTime = 0;
        this.cause = e;
        this.status = SoTaskStatus.Failed;
    }

    @Override
    public final void run() {
        this.doWork(this.retryCnt);
        this.retryCnt++;
    }

    protected abstract void doWork(int retryCnt);

    public enum SoTaskStatus {
        Finish,
        Failed,
        Continue
    }
}