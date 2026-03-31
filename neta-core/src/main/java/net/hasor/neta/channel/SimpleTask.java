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
 * Adapter that wraps a plain {@link Runnable} as a {@link DefaultSoTask}.
 * <p>The wrapped runnable is invoked once and only once during the first call to
 * {@link #doWork(int)}. If it completes normally, the task reports
 * {@link DefaultSoTask#finishTask()}; if it throws an {@link Exception}, the task reports
 * {@link DefaultSoTask#failedTask(Exception)}. The task is never retried in either case.</p>
 * <p>This is a convenience class for submitting one-shot actions through
 * {@link SoContextService#submitSoTask(DefaultSoTask, Object)}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-09
 * @see DefaultSoTask
 */
public class SimpleTask extends DefaultSoTask {
    private final Runnable runnable;

    /** Wrap {@code runnable} as a one-shot event-loop task. */
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