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
package net.hasor.neta.channel.tcp;

import net.hasor.neta.channel.SoConfig;

/**
 * TCP specific configuration options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class TcpSoConfig extends SoConfig {
    // swapBuffer
    private int swapRcvBuf = 64 * 1024;
    private int swapSndBuf = 64 * 1024;

    private Boolean soKeepAlive       = null;      // SO_KEEPALIVE: 设置 tcp keep-alive（对应 SO_KEEPALIVE 参数）
    private Integer soKeepIdleSec     = null;      // TCP_KEEPIDLE: 设置连接上如果没有数据发送的话，多久后发送 keepalive 探测包，单位是：秒
    private Integer soKeepIntervalSec = null;      // TCP_KEEPINTERVAL: 前后两次探测之间的时间间隔，单位是：秒
    private Integer soKeepCount       = null;      // TCP_KEEPCOUNT: 关闭一个非活跃连接之前的最大重试次数
    // TCP_NODELAY  //禁用 Nagle 算法
    //    SO_LINGER

    public TcpSoConfig() {
        super(TcpProvider.NAME);
    }

    /** Returns the swap buffer size for receiving data (bytes). */
    public int getSwapRcvBuf() {
        return this.swapRcvBuf;
    }

    /** Sets the swap buffer size for receiving data (bytes). */
    public void setSwapRcvBuf(int swapRcvBuf) {
        this.swapRcvBuf = swapRcvBuf;
    }

    /** Returns the swap buffer size for sending data (bytes). */
    public int getSwapSndBuf() {
        return this.swapSndBuf;
    }

    /** Sets the swap buffer size for sending data (bytes). */
    public void setSwapSndBuf(int swapSndBuf) {
        this.swapSndBuf = swapSndBuf;
    }

    /** Returns the SO_KEEPALIVE setting, or null if unset. */
    public Boolean getSoKeepAlive() {
        return this.soKeepAlive;
    }

    /** Enables or disables SO_KEEPALIVE. */
    public void setSoKeepAlive(Boolean soKeepAlive) {
        this.soKeepAlive = soKeepAlive;
    }

    /** Returns TCP_KEEPIDLE in seconds (idle time before keepalive probes), or null if unset. */
    public Integer getSoKeepIdleSec() {
        return this.soKeepIdleSec;
    }

    /** Sets TCP_KEEPIDLE in seconds. */
    public void setSoKeepIdleSec(Integer soKeepIdleSec) {
        this.soKeepIdleSec = soKeepIdleSec;
    }

    /** Returns TCP_KEEPINTERVAL in seconds (interval between probes), or null if unset. */
    public Integer getSoKeepIntervalSec() {
        return this.soKeepIntervalSec;
    }

    /** Sets TCP_KEEPINTERVAL in seconds. */
    public void setSoKeepIntervalSec(Integer soKeepIntervalSec) {
        this.soKeepIntervalSec = soKeepIntervalSec;
    }

    /** Returns TCP_KEEPCOUNT (max retries before closing inactive connection), or null if unset. */
    public Integer getSoKeepCount() {
        return this.soKeepCount;
    }

    /** Sets TCP_KEEPCOUNT. */
    public void setSoKeepCount(Integer soKeepCount) {
        this.soKeepCount = soKeepCount;
    }

}