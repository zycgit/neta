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
 * Socket Task
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class DefaultSoTask implements Runnable {
    public enum SoTaskStatus {
        Finish,
        Exit,
        Continue
    }

    private SoTaskStatus status;
    private boolean      delay;
    private int          delayTime;
    private Exception    cause;
    private int          retryCnt;

    public Throwable getCause() {
        return this.cause;
    }

    public SoTaskStatus getStatus() {
        return this.status;
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

    private long delayTimeEnter;

    @Override
    public final void run() {
        if (this.delay && this.delayTime > 0) {
            if (this.delayTimeEnter == 0) {
                this.delayTimeEnter = System.currentTimeMillis();
                this.status = SoTaskStatus.Continue;
                return;
            }
            if ((this.delayTimeEnter + this.delayTime) > System.currentTimeMillis()) {
                this.delay = false;
                this.delayTime = 0;
                this.delayTimeEnter = 0;
            }
        }

        this.doWork(this.retryCnt);
        this.retryCnt++;
    }

    protected abstract void doWork(int retryCnt);
}