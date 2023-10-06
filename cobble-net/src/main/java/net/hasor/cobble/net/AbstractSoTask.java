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

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.logging.Logger;

/**
 * Socket Task
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractSoTask implements Runnable {
    private static final Logger logger = Logger.getLogger(AbstractSoTask.class);

    public enum SoTaskStatus {
        Finish,
        Exit,
        Continue
    }

    private SoTaskStatus status;
    private boolean      delay;
    private int          delayTime;
    private Exception    cause;
    private boolean      retry;

    public Throwable getCause() {
        return this.cause;
    }

    public SoTaskStatus getStatus() {
        return this.status;
    }

    protected void delayTask() {
        this.delay = true;
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    protected void delayTask(int delayTime) {
        this.delay = true;
        this.delayTime = delayTime;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    protected void continueTask() {
        this.delay = false;
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Continue;
    }

    protected void finishTask() {
        this.delay = false;
        this.delayTime = 0;
        this.cause = null;
        this.status = SoTaskStatus.Finish;
    }

    protected void exitTask(Exception e) {
        this.delay = false;
        this.delayTime = 0;
        this.cause = e;
        this.status = SoTaskStatus.Exit;
    }

    @Override
    public final void run() {
        if (this.delay && this.delayTime > 0) {
            ThreadUtils.sleep(this.delayTime);
            if (Thread.currentThread().isInterrupted()) {
                this.exitTask(new IllegalStateException("thread is Interrupted"));
                return;
            }
        }

        this.doWork(this.retry);
        this.retry = true;
    }

    protected abstract void doWork(boolean retry);

}