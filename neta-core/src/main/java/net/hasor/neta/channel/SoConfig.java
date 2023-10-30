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
 * Socket Config
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class SoConfig {
    // buffers
    private ByteBufAllocator bufAllocator;
    private int              rcvSwapBuf        = 16 * 1024;// socket 接收缓冲区大小,对应 SO_RCVBUF 参数
    private int              sndSwapBuf        = 16 * 1024;// socket 发送缓冲区大小,对应 SO_SNDBUF 参数
    private int              rcvLocalBuf       = 16 * 1024;// 本地读取缓冲区，从网络上收到的数据会先进入 swap 缓冲区，然后将其移动到 rcvLocal 在交给应用程序处理。
    private int              sndLocalBuf       = 16 * 1024;// 本地发送缓冲区，程序发送的数据需要先放入 rcvLocal 然后将其移动到 swap 缓冲区进行发送。
    // so configs
    private Integer          soRcvBuf          = null;     // SO_RCVBUF: The size of the socket send buffer
    private Integer          soSndBuf          = null;     // SO_SNDBUF: The size of the socket receive buffer
    private Boolean          soKeepAlive       = null;     // SO_KEEPALIVE: 设置 tcp keep-alive（对应 SO_KEEPALIVE 参数）
    private Integer          soKeepIdleSec     = null;     // TCP_KEEPIDLE: 设置连接上如果没有数据发送的话，多久后发送 keepalive 探测包，单位是：秒
    private Integer          soKeepIntervalSec = null;     // TCP_KEEPINTERVAL: 前后两次探测之间的时间间隔，单位是：秒
    private Integer          soKeepCount       = null;     // TCP_KEEPCOUNT: 关闭一个非活跃连接之前的最大重试次数
    private Integer          soReadTimeoutMs   = -1;       // socket read timeout
    private Integer          soWriteTimeoutMs  = -1;       // socket write timeout
    //    SO_LINGER
    // timeout
    private int              retryIntervalMs   = 50;       // cobble.net 内部任务延迟调度的延迟间隔
    private int              connectTimeoutMs  = 10 * 1000;// 建立连接超时时间
    // threads
    private SoThreadFactory  threadFactory;                // IO 线程，负责处理 AIO 回调事件，通常是：创建链接及 swap 缓冲区和 socket 缓冲区之间的数据交换
    private ClassLoader      classLoader;                  // IO 线程，负责处理 AIO 回调事件，通常是：创建链接及 swap 缓冲区和 socket 缓冲区之间的数据交换
    private int              ioThreads;                    // Worker 线程，负责处理 cobble.net 中的各种 Task，包括 swap 缓冲区和 rcv/snd 缓冲区之间的数据交换及其它各类 IOTask 任务
    private int              taskThreads;                  // Worker 线程，负责处理 cobble.net 中的各种 Task，包括 swap 缓冲区和 rcv/snd 缓冲区之间的数据交换及其它各类 IOTask 任务
    // other
    private boolean          netlog            = false;    // 打印网络日志

    public void setSwapBuf(int rcvSwapBuf, int sndSwapBuf) {
        this.rcvSwapBuf = rcvSwapBuf;
        this.sndSwapBuf = sndSwapBuf;
    }

    public void setLocalBuf(int rcvLocalBuf, int sndLocalBuf) {
        this.rcvLocalBuf = rcvLocalBuf;
        this.sndLocalBuf = sndLocalBuf;
    }

    public int getRcvSwapBuf() {
        return this.rcvSwapBuf;
    }

    public void setRcvSwapBuf(int rcvSwapBuf) {
        this.rcvSwapBuf = rcvSwapBuf;
    }

    public int getSndSwapBuf() {
        return this.sndSwapBuf;
    }

    public void setSndSwapBuf(int sndSwapBuf) {
        this.sndSwapBuf = sndSwapBuf;
    }

    public int getRcvLocalBuf() {
        return this.rcvLocalBuf;
    }

    public void setRcvLocalBuf(int rcvLocalBuf) {
        this.rcvLocalBuf = rcvLocalBuf;
    }

    public int getSndLocalBuf() {
        return this.sndLocalBuf;
    }

    public void setSndLocalBuf(int sndLocalBuf) {
        this.sndLocalBuf = sndLocalBuf;
    }

    public Integer getSoRcvBuf() {
        return this.soRcvBuf;
    }

    public void setSoRcvBuf(Integer soRcvBuf) {
        this.soRcvBuf = soRcvBuf;
    }

    public Integer getSoSndBuf() {
        return this.soSndBuf;
    }

    public void setSoSndBuf(Integer soSndBuf) {
        this.soSndBuf = soSndBuf;
    }

    public Boolean getSoKeepAlive() {
        return this.soKeepAlive;
    }

    public void setSoKeepAlive(Boolean soKeepAlive) {
        this.soKeepAlive = soKeepAlive;
    }

    public Integer getSoKeepIdleSec() {
        return this.soKeepIdleSec;
    }

    public void setSoKeepIdleSec(Integer soKeepIdleSec) {
        this.soKeepIdleSec = soKeepIdleSec;
    }

    public Integer getSoKeepIntervalSec() {
        return this.soKeepIntervalSec;
    }

    public void setSoKeepIntervalSec(Integer soKeepIntervalSec) {
        this.soKeepIntervalSec = soKeepIntervalSec;
    }

    public Integer getSoKeepCount() {
        return this.soKeepCount;
    }

    public void setSoKeepCount(Integer soKeepCount) {
        this.soKeepCount = soKeepCount;
    }

    public Integer getSoReadTimeoutMs() {
        return this.soReadTimeoutMs;
    }

    public void setSoReadTimeoutMs(Integer soReadTimeoutMs) {
        this.soReadTimeoutMs = soReadTimeoutMs;
    }

    public Integer getSoWriteTimeoutMs() {
        return this.soWriteTimeoutMs;
    }

    public void setSoWriteTimeoutMs(Integer soWriteTimeoutMs) {
        this.soWriteTimeoutMs = soWriteTimeoutMs;
    }

    public int getRetryIntervalMs() {
        return this.retryIntervalMs;
    }

    public void setRetryIntervalMs(int retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public int getConnectTimeoutMs() {
        return this.connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public ByteBufAllocator getBufAllocator() {
        return this.bufAllocator;
    }

    public void setBufAllocator(ByteBufAllocator bufAllocator) {
        this.bufAllocator = bufAllocator;
    }

    public SoThreadFactory getThreadFactory() {
        return this.threadFactory;
    }

    public void setThreadFactory(SoThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public int getIoThreads() {
        return this.ioThreads;
    }

    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }

    public int getTaskThreads() {
        return this.taskThreads;
    }

    public void setTaskThreads(int taskThreads) {
        this.taskThreads = taskThreads;
    }

    public boolean isNetlog() {
        return this.netlog;
    }

    public void setNetlog(boolean netlog) {
        this.netlog = netlog;
    }
}