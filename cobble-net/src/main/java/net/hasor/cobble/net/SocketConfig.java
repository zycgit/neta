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
import net.hasor.cobble.bytebuf.ByteBufAllocator;

import java.util.concurrent.ExecutorService;

/**
 * Socket Config
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SocketConfig {
    private int              swapBufSize      = 4 * 1024;   // socket 缓冲区大小
    private int              rcvBufSize       = 16 * 1024;  // 读取缓冲区
    private int              sndBufSize       = 16 * 1024;  // 发送缓冲区
    private int              retryIntervalMs  = 50;         // cobble.net 内部任务延迟调度的延迟间隔
    private int              connectTimeoutMs = 10 * 1000;  // 建立连接超时时间
    private int              readTimeoutSec   = -1;         // socket read timeout
    private int              writeTimeoutSec  = -1;         // socket write timeout
    private int              soTimeoutSec     = -1;         // so timeout
    private ByteBufAllocator bufAllocator;
    // 缓冲区关系： socket <---> swap <---> rcv/snd
    //               IO Thread    WorkerThread
    private ExecutorService  ioExecutor; // IO 线程，负责处理创建链接及 swap 缓冲区和 socket 缓冲区之间的数据交换
    private ExecutorService  workerExecutor; // Worker 线程，负责处理 swap 缓冲区和 rcv/snd 缓冲区之间的数据交换，以及各类 IOTask 任务

    public void setSwapBufSize(int swapBufSize) {
        this.swapBufSize = swapBufSize;
    }

    public int getSwapBufSize() {
        return this.swapBufSize;
    }

    public void setRcvBufSize(int rcvBufSize) {
        this.rcvBufSize = rcvBufSize;
    }

    public int getRcvBufSize() {
        return this.rcvBufSize;
    }

    public int getSndBufSize() {
        return this.sndBufSize;
    }

    public void setSndBufSize(int sndBufSize) {
        this.sndBufSize = sndBufSize;
    }

    public int getRetryIntervalMs() {
        return this.retryIntervalMs;
    }

    public void setRetryIntervalMs(int retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public void setBufAllocator(ByteBufAllocator bufAllocator) {
        this.bufAllocator = bufAllocator;
    }

    public int getConnectTimeoutMs() {
        return this.connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutSec() {
        return this.readTimeoutSec;
    }

    public void setReadTimeoutSec(int readTimeoutSec) {
        this.readTimeoutSec = readTimeoutSec;
    }

    public int getWriteTimeoutSec() {
        return this.writeTimeoutSec;
    }

    public void setWriteTimeoutSec(int writeTimeoutSec) {
        this.writeTimeoutSec = writeTimeoutSec;
    }

    public int getSoTimeoutSec() {
        return this.soTimeoutSec;
    }

    public void setSoTimeoutSec(int soTimeoutSec) {
        this.soTimeoutSec = soTimeoutSec;
    }

    public ByteBufAllocator getBufAllocator() {
        return this.bufAllocator;
    }

    public void setIoExecutor(ExecutorService ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public void setWorkerExecutor(ExecutorService workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    public ExecutorService getWorkerExecutor() {
        return this.workerExecutor;
    }
}
