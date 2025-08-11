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
import net.hasor.neta.channel.tcp.TcpOptions;
import net.hasor.neta.channel.udp.UdpOptions;

/**
 * Listener options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoConfig {
    // for Listener
    private final String  protocol;
    private       boolean suspend = false;

    // for Socket
    private int     soRcvBuf          = 32 * 1024 * 1024; // SO_RCVBUF: The size of the socket receive buffer
    private int     soSndBuf          = 32 * 1024 * 1024; // SO_SNDBUF: The size of the socket send buffer
    private Boolean soKeepAlive       = null;      // SO_KEEPALIVE: 设置 tcp keep-alive（对应 SO_KEEPALIVE 参数）
    private Integer soKeepIdleSec     = null;      // TCP_KEEPIDLE: 设置连接上如果没有数据发送的话，多久后发送 keepalive 探测包，单位是：秒
    private Integer soKeepIntervalSec = null;      // TCP_KEEPINTERVAL: 前后两次探测之间的时间间隔，单位是：秒
    private Integer soKeepCount       = null;      // TCP_KEEPCOUNT: 关闭一个非活跃连接之前的最大重试次数
    private Integer soReadTimeoutMs   = -1;        // socket read timeout
    private Integer soWriteTimeoutMs  = -1;        // socket write timeout
    //    SO_LINGER

    private int connectTimeoutMs = 10 * 1000; // 建立连接超时时间

    protected SoConfig(String protocol) {
        this.protocol = protocol;
    }

    public static TcpOptions TCP() {
        return new TcpOptions();
    }

    public static UdpOptions UDP() {
        return new UdpOptions();
    }

    public String getProtocol() {
        return this.protocol;
    }

    public boolean isSuspend() {
        return this.suspend;
    }

    public void setSuspend(boolean suspend) {
        this.suspend = suspend;
    }

    //

    public void setSoBufSize(int soRcvBuf, int soSndBuf) {
        this.soRcvBuf = soRcvBuf;
        this.soSndBuf = soSndBuf;
    }

    public int getSoRcvBuf() {
        return this.soRcvBuf;
    }

    public void setSoRcvBuf(int soRcvBuf) {
        this.soRcvBuf = soRcvBuf;
    }

    public int getSoSndBuf() {
        return this.soSndBuf;
    }

    public void setSoSndBuf(int soSndBuf) {
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

    public int getConnectTimeoutMs() {
        return this.connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}