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
import java.io.Closeable;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.logging.Logger;

/**
 * Low-latency fixed-thread-pool task dispatcher used by {@link SoContextService} to execute all
 * Neta pipeline and subscription tasks.
 * <h3>Architecture</h3>
 * <ul>
 *   <li>All tasks are added to one lock-free {@link ConcurrentLinkedQueue} shared by all worker threads.</li>
 *   <li>When the queue is empty, workers suspend through {@link LockSupport#park}, avoiding the
 *       extra overhead of condition variables or blocking queues.</li>
 *   <li>When a task is submitted, the dispatcher wakes only <b>one</b> worker thread using the
 *       round-robin index {@code wakeIndex}, spreading wake-up pressure and avoiding a thundering
 *       herd when many tasks arrive in a short period.</li>
 *   <li>Delayed tasks are registered on the shared {@link HashedWheelTimer} and re-queued
 *       automatically when the delay expires.</li>
 * </ul>
 * <h3>Shutdown</h3>
 * {@link #close()} first clears the running flag, then wakes all worker threads, and waits up to
 * three seconds for each thread to drain gracefully. If tasks still remain, they continue running
 * on the calling thread so work is not silently dropped.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-09
 * @see SoContextService
 */
class SoTaskExecutor implements Closeable {
    private static final Logger               logger = Logger.getLogger(SoTaskExecutor.class);
    private final        HashedWheelTimer     timer;
    private final        Queue<TaskWorker<?>> tasks;
    //
    private final        AtomicBoolean        runTag;
    private final        Thread[]             workerThreads;
    private final        AtomicInteger        wakeIndex;

    public SoTaskExecutor(ClassLoader classLoader, SoThreadFactory soThreadFactory, int taskThreads, HashedWheelTimer timer) {
        this.timer = timer;
        this.tasks = new ConcurrentLinkedQueue<>();
        this.runTag = new AtomicBoolean(false);
        this.workerThreads = new Thread[taskThreads];
        this.wakeIndex = new AtomicInteger(0);

        if (this.runTag.compareAndSet(false, true)) {
            ThreadFactory workerThreadFactory = soThreadFactory.newFactory(classLoader, "Neta-Worker-%s");
            for (int i = 0; i < taskThreads; i++) {
                this.workerThreads[i] = workerThreadFactory.newThread(this::doWork);
                this.workerThreads[i].start();
            }
        }
    }

    public void close() {
        this.runTag.set(false);

        // wake up workers without interrupting, let them drain remaining tasks gracefully
        for (Thread thread : this.workerThreads) {
            LockSupport.unpark(thread);
        }

        for (Thread thread : this.workerThreads) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (thread.isAlive()) {
                thread.interrupt();
                logger.info("wait workerThread close... (" + thread.getName() + ")");
            }
        }

        // safety drain: run any tasks still remaining after workers exit
        TaskWorker<?> remaining;
        while ((remaining = this.tasks.poll()) != null) {
            remaining.run();
        }

        logger.info("workerThread closed.");
    }

    private void doWork() {
        while (this.runTag.get()) {
            TaskWorker<?> poll = this.tasks.poll();
            if (poll != null) {
                poll.run();
            } else {
                if (this.tasks.isEmpty()) {
                    LockSupport.park();
                    if (Thread.currentThread().isInterrupted()) {
                        logger.warn("task thread interrupted, (" + Thread.currentThread().getName() + ")");
                        return;
                    }
                }
            }
        }

        // graceful shutdown: drain and execute remaining tasks before exit
        TaskWorker<?> poll;
        while ((poll = this.tasks.poll()) != null) {
            poll.run();
        }

        logger.info("task thread exit, (" + Thread.currentThread().getName() + ")");
    }

    public <T> Future<T> submitSoTask(DefaultSoTask task, T result) {
        Future<T> future = new BasicFuture<>();
        this.submitSoTask(task, future, result);
        return future;
    }

    private <T> void submitSoTask(DefaultSoTask task, Future<T> future, T result) {
        // reject new submissions after shutdown to prevent infinite task chains(e.g. receiveLoop re-submitting itself via onFinal listener)
        if (!this.runTag.get()) {
            future.failed(new IllegalStateException("Executor has been shut down, task rejected."));
            return;
        }

        TaskWorker<T> worker = new TaskWorker<>(this, task, future, result);

        int delayTime = task.getDelayTime();
        if (delayTime > 0) {
            this.timer.newTimeout(t -> {
                if (this.runTag.get()) {
                    this.tasks.add(worker);
                    wakeUp();
                }
            }, delayTime, task.getDelayUnit());
        } else {
            this.tasks.add(worker);
            wakeUp();
        }
    }

    private void wakeUp() {
        int len = this.workerThreads.length;
        int idx = (this.wakeIndex.getAndIncrement() & 0x7FFFFFFF) % len;
        LockSupport.unpark(this.workerThreads[idx]);
    }

    private static class TaskWorker<T> implements Runnable {
        private final SoTaskExecutor executor;
        private final DefaultSoTask  task;
        private final Future<T>      future;
        private final T              result;

        public TaskWorker(SoTaskExecutor executor, DefaultSoTask task, Future<T> future, T result) {
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