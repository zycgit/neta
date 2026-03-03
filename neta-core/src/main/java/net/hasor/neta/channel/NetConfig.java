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
 * Global configuration for the {@link NetManager}: thread pools, buffer allocation and misc options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetConfig {
    // buffers
    private ByteBufAllocator bufAllocator;
    // timeout
    private int              retryIntervalMs = 50;        // cobble.net 内部任务延迟调度的延迟间隔
    // threads
    private SoThreadFactory  threadFactory;                 // IO 线程，负责处理 AIO 回调事件，通常是：创建链接及 swap 缓冲区和 socket 缓冲区之间的数据交换
    private ClassLoader      classLoader;                   // IO 线程，负责处理 AIO 回调事件，通常是：创建链接及 swap 缓冲区和 socket 缓冲区之间的数据交换
    private int              ioThreads;                     // Worker 线程，负责处理 cobble.net 中的各种 Task，包括 swap 缓冲区和 rcv/snd 缓冲区之间的数据交换及其它各类 IOTask 任务
    private int              taskThreads;                   // Worker 线程，负责处理 cobble.net 中的各种 Task，包括 swap 缓冲区和 rcv/snd 缓冲区之间的数据交换及其它各类 IOTask 任务
    // other
    private boolean          printLog        = false;       // 打印网络日志

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