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
 * A one-shot task that wraps a plain {@link Runnable} for execution in the event loop.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-09
 */
public class SimpleTask extends DefaultSoTask {
    private final Runnable runnable;

    /** Wraps {@code runnable} as a one-shot event-loop task. */
    public SimpleTask(Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    protected void doWork(int retryCnt) {
        try {
            this.runnable.run();
            this.finishTask();
        } catch (Exception e) {
            this.failedTask(e);
        }
    }
}