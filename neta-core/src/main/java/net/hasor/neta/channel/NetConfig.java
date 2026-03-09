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
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Global configuration bean consumed by {@link NetManager} at startup.
 * <p>Key properties and their defaults:
 * <table border="1" summary="NetConfig properties">
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code ioThreads}</td><td>max(cpu/4, 1)</td>
 *       <td>Size of the AIO completion-handler thread pool ({@code Neta-IO-*}).
 *           Controls accept / connect / read / write concurrency.</td></tr>
 *   <tr><td>{@code taskThreads}</td><td>cpu count</td>
 *       <td>Size of the worker thread pool ({@code Neta-Worker-*}) used for pipeline
 *           tasks and {@link PlayLoad} event dispatch.</td></tr>
 *   <tr><td>{@code retryIntervalMs}</td><td>50 ms</td>
 *       <td>Base delay between retry ticks for internal {@link SoDelayTask} instances.</td></tr>
 *   <tr><td>{@code bufAllocator}</td><td>{@link ByteBufAllocator#DEFAULT}</td>
 *       <td>Buffer allocator used for all channel read/write buffers.</td></tr>
 *   <tr><td>{@code threadFactory}</td><td>daemon threads</td>
 *       <td>Factory that creates IO and worker threads; override via
 *           {@link #setThreadFactory} to customise names, priorities, or class loaders.</td></tr>
 *   <tr><td>{@code classLoader}</td><td>SoContextService’s classloader</td>
 *       <td>ClassLoader set on all Neta-managed threads.</td></tr>
 *   <tr><td>{@code printLog}</td><td>false</td>
 *       <td>Enables verbose low-level network-layer logging.</td></tr>
 * </table>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see NetManager
 * @see SoContextService
 */
public class NetConfig {
    // Buffer allocator.
    private ByteBufAllocator bufAllocator;
    // Internal retry scheduler interval.
    private int              retryIntervalMs = 50;
    // Thread settings.
    private SoThreadFactory  threadFactory;
    private ClassLoader      classLoader;
    private int              ioThreads;
    private int              taskThreads;
    // Misc settings.
    private boolean          printLog        = false;

    /** Interval in milliseconds between internal retry schedule ticks. */
    public int getRetryIntervalMs() {
        return this.retryIntervalMs;
    }

    /** Sets the retry interval (ms) for internal delay tasks. */
    public void setRetryIntervalMs(int retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    /** Returns the allocator used to create read/write byte buffers. */
    public ByteBufAllocator getBufAllocator() {
        return this.bufAllocator;
    }

    /** Overrides the default {@link ByteBufAllocator}. */
    public void setBufAllocator(ByteBufAllocator bufAllocator) {
        this.bufAllocator = bufAllocator;
    }

    /** Returns the thread factory used to create IO and task threads. */
    public SoThreadFactory getThreadFactory() {
        return this.threadFactory;
    }

    /** Overrides the default thread factory. */
    public void setThreadFactory(SoThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    /** Returns the class loader used by IO threads. */
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    /** Overrides the class loader used by internal threads. */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Returns the number of AIO completion handler threads (0 = default). */
    public int getIoThreads() {
        return this.ioThreads;
    }

    /** Sets the number of AIO completion handler threads. */
    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }

    /** Returns the number of task worker threads (0 = default). */
    public int getTaskThreads() {
        return this.taskThreads;
    }

    /** Sets the number of task worker threads. */
    public void setTaskThreads(int taskThreads) {
        this.taskThreads = taskThreads;
    }

    /** Returns whether verbose network logging is enabled. */
    public boolean isPrintLog() {
        return this.printLog;
    }

    /** Enables or disables verbose network logging. */
    public void setPrintLog(boolean printLog) {
        this.printLog = printLog;
    }
}