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
 * Global configuration bean used by {@link NetManager} during startup.
 * <p>Key properties and their default values:</p>
 * <table border="1" summary="NetConfig properties">
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code ioThreads}</td><td>max(cpu/4, 1)</td>
 *       <td>Size of the AIO completion handling thread pool ({@code Neta-IO-*}).
 *           Controls concurrency for accept, connect, read, and write operations.</td></tr>
 *   <tr><td>{@code taskThreads}</td><td>cpu count</td>
 *       <td>Size of the worker thread pool ({@code Neta-Worker-*}) used for pipeline tasks
 *           and {@link PlayLoad} event dispatch.</td></tr>
 *   <tr><td>{@code retryIntervalMs}</td><td>50 ms</td>
 *       <td>Base delay between retry ticks for internal {@link SoDelayTask} instances.</td></tr>
 *   <tr><td>{@code bufAllocator}</td><td>{@link ByteBufAllocator#DEFAULT}</td>
 *       <td>Buffer allocator used for channel read and write buffers.</td></tr>
 *   <tr><td>{@code threadFactory}</td><td>daemon threads</td>
 *       <td>Factory used to create IO and worker threads. It can be customized through
 *           {@link #setThreadFactory} to control thread names, priority, or class loader.</td></tr>
 *   <tr><td>{@code classLoader}</td><td>SoContextService class loader</td>
 *       <td>ClassLoader assigned to all Neta-managed threads.</td></tr>
 *   <tr><td>{@code printLog}</td><td>false</td>
 *       <td>Enables verbose low-level network logging.</td></tr>
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

    /** Return the interval between internal retry scheduler ticks, in milliseconds. */
    public int getRetryIntervalMs() {
        return this.retryIntervalMs;
    }

    /** Set the retry interval for internal delayed tasks, in milliseconds. */
    public void setRetryIntervalMs(int retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    /** Return the allocator used to create read and write byte buffers. */
    public ByteBufAllocator getBufAllocator() {
        return this.bufAllocator;
    }

    /** Override the default {@link ByteBufAllocator}. */
    public void setBufAllocator(ByteBufAllocator bufAllocator) {
        this.bufAllocator = bufAllocator;
    }

    /** Return the thread factory used to create IO and worker threads. */
    public SoThreadFactory getThreadFactory() {
        return this.threadFactory;
    }

    /** Override the default thread factory. */
    public void setThreadFactory(SoThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    /** Return the class loader used by IO threads. */
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    /** Override the class loader used by internal threads. */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Return the number of AIO completion threads, where 0 means the default value. */
    public int getIoThreads() {
        return this.ioThreads;
    }

    /** Set the number of AIO completion threads. */
    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }

    /** Return the number of worker task threads, where 0 means the default value. */
    public int getTaskThreads() {
        return this.taskThreads;
    }

    /** Set the number of worker task threads. */
    public void setTaskThreads(int taskThreads) {
        this.taskThreads = taskThreads;
    }

    /** Return whether verbose network logging is enabled. */
    public boolean isPrintLog() {
        return this.printLog;
    }

    /** Enable or disable verbose network logging. */
    public void setPrintLog(boolean printLog) {
        this.printLog = printLog;
    }
}