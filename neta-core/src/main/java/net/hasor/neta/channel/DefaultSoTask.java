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
 * Base class for retryable event-loop tasks executed by {@link SoTaskExecutor}.
 * <p>When {@link #doWork} finishes, the task calls one of the state-setting methods below to
 * report its outcome back to the framework:</p>
 * <ul>
 *   <li>{@link #finishTask()} - the task completed successfully and will not run again.</li>
 *   <li>{@link #continueTask()} - the task is not finished yet and should be queued again immediately.</li>
 *   <li>{@link #delayTask(int, TimeUnit)} - the task should be queued again after the specified
 *       delay, implemented through the shared
 *       {@link net.hasor.cobble.concurrent.timer.HashedWheelTimer}.</li>
 *   <li>{@link #failedTask(Exception)} - the task failed. The owning executor completes the related
 *       Future exceptionally and does not run the task again.</li>
 * </ul>
 * <p>The {@code retryCnt} passed to {@link #doWork} starts at 0 and is incremented after each retry
 * caused by continue or delay. Subclasses can use it to implement backoff policies or maximum
 * retry limits.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoTaskExecutor
 * @see SimpleTask
 * @see SoDelayTask
 */
public abstract class DefaultSoTask implements Runnable {
    private SoTaskStatus status = SoTaskStatus.Finish;
    private int          delayTime;
    private TimeUnit     delayUnit;
    private Exception    cause;
    private int          retryCnt;

    /** Return the failure cause if the previous {@code doWork} call ended with {@link #failedTask}. */
    public Throwable getCause() {
        return this.cause;
    }

    /** Return the current execution status of this task. */
    public SoTaskStatus getStatus() {
        return this.status;
    }

    /** Return the delay duration configured by the most recent {@link #delayTask} call. */
    public int getDelayTime() {
        return this.delayTime;
    }

    /** Return the delay time unit configured by the most recent {@link #delayTask} call. */
    public TimeUnit getDelayUnit() {
        return this.delayUnit;
    }

    /**
     * Mark the task for delayed retry.
     * @param delay delay duration
     * @param unit delay time unit
     */
    protected void delayTask(int delay, TimeUnit unit) {
        this.delayTime = delay;
        this.delayUnit = unit;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    /** Mark the task to continue immediately. */
    protected void continueTask() {
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    /** Mark the task as successfully finished. */
    protected void finishTask() {
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Finish;
    }

    /**
     * Mark the task as failed.
     * @param e failure cause
     */
    protected void failedTask(Exception e) {
        this.delayTime = 0;
        this.cause = e;
        this.status = SoTaskStatus.Failed;
    }

    /** Execute the task body once and advance the retry counter. */
    @Override
    public final void run() {
        this.doWork(this.retryCnt);
        this.retryCnt++;
    }

    /**
     * Execute the main task logic.
     * @param retryCnt current retry count, starting at 0 and increasing on each retry
     */
    protected abstract void doWork(int retryCnt);

    public enum SoTaskStatus {
        Finish,
        Failed,
        Continue
    }
}