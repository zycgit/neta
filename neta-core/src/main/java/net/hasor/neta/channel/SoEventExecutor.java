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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.logging.Logger;

import java.io.Closeable;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * 内部使用的低延迟任务分发执行器
 * @version : 2023-10-09
 * @author 赵永春 (zyc@hasor.net)
 */
class SoEventExecutor implements Closeable {
    private static final Logger               logger = Logger.getLogger(SoEventExecutor.class);
    private final        HashedWheelTimer     timer;
    private final        Queue<TaskWorker<?>> tasks;
    //
    private final        AtomicBoolean        runTag;
    private final        Thread[]             workerThreads;

    public SoEventExecutor(String ctxName, ClassLoader classLoader, SoThreadFactory soThreadFactory, int taskThreads) {
        ThreadFactory threadFactory = soThreadFactory.newFactory(classLoader, "Cobble[" + ctxName + "]-AIO-Workers-%s");
        this.timer = new HashedWheelTimer(threadFactory, 50, TimeUnit.MILLISECONDS);
        this.tasks = new ConcurrentLinkedQueue<>();
        this.runTag = new AtomicBoolean(false);
        this.workerThreads = new Thread[taskThreads];

        if (this.runTag.compareAndSet(false, true)) {
            ThreadFactory workerThreadFactory = soThreadFactory.newFactory(classLoader, "Cobble[" + ctxName + "]-AIO-Workers-%s");
            for (int i = 0; i < taskThreads; i++) {
                this.workerThreads[i] = workerThreadFactory.newThread(this::doWork);
                this.workerThreads[i].start();
            }
        }
    }

    public void close() {
        this.runTag.set(false);

        long t = System.currentTimeMillis();
        while (true) {
            int terminated = 0;

            for (Thread thread : this.workerThreads) {
                if (!thread.isInterrupted()) {
                    thread.interrupt();
                }
                if (thread.getState() == Thread.State.WAITING) {
                    LockSupport.unpark(thread);
                }

                if (thread.getState() == Thread.State.TERMINATED) {
                    terminated++;
                }
            }

            if (terminated == this.workerThreads.length) {
                break;
            }

            long cost = System.currentTimeMillis() - t;
            if (cost > 3000) {
                t = System.currentTimeMillis();
                logger.info("wait workerThread close...");
            }
            ThreadUtils.sleep(100);
        }

        logger.info("workerThread closed.");
    }

    private void doWork() {
        while (this.runTag.get()) {
            TaskWorker<?> poll = this.tasks.poll();
            if (poll != null) {
                poll.run();
            }

            if (this.tasks.isEmpty()) {
                LockSupport.park();
                if (Thread.currentThread().isInterrupted()) {
                    logger.warn("task thread interrupted, (" + Thread.currentThread().getName() + ")");
                    return;
                }
            }
        }
        logger.info("task thread exit, (" + Thread.currentThread().getName() + ")");
    }

    public <T> Future<T> submitSoTask(DefaultSoTask task, T result) {
        Future<T> future = new BasicFuture<>();
        this.submitSoTask(task, future, result);
        return future;
    }

    private <T> void submitSoTask(DefaultSoTask task, Future<T> future, T result) {
        TaskWorker<T> worker = new TaskWorker<>(this, task, future, result);

        int delayTime = task.getDelayTime();
        if (delayTime > 0) {
            this.timer.newTimeout(t -> this.tasks.add(worker), delayTime, task.getDelayUnit());
        } else {
            this.tasks.add(worker);
        }

        for (Thread workerThread : this.workerThreads) {
            if (workerThread.getState() == Thread.State.WAITING) {
                LockSupport.unpark(workerThread);
                break;
            }
        }
    }

    private static class TaskWorker<T> implements Runnable {
        private final SoEventExecutor executor;
        private final DefaultSoTask   task;
        private final Future<T>       future;
        private final T               result;

        public TaskWorker(SoEventExecutor executor, DefaultSoTask task, Future<T> future, T result) {
            this.executor = executor;
            this.task = task;
            this.future = future;
            this.result = result;
        }

        @Override
        public void run() {
            try {
                this.task.run();
                switch (task.getStatus()) {
                    case Continue:
                        this.executor.submitSoTask(this.task, this.future, this.result);
                        break;
                    case Finish:
                        this.future.completed(this.result);
                        break;
                    case Failed:
                        this.future.failed(this.task.getCause());
                        break;
                }
            } catch (Throwable e) {
                this.future.failed(e);
            }
        }

        public Future<T> getFuture() {
            return this.future;
        }
    }
}