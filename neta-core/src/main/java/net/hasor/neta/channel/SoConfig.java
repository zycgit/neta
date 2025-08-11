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
import net.hasor.neta.channel.tcp.TcpSoConfig;
import net.hasor.neta.channel.udp.UdpSoConfig;

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
    private int     soRcvBuf         = 32 * 1024 * 1024; // SO_RCVBUF: The size of the socket receive buffer
    private int     soSndBuf         = 32 * 1024 * 1024; // SO_SNDBUF: The size of the socket send buffer
    private Integer soReadTimeoutMs  = -1;        // socket read timeout
    private Integer soWriteTimeoutMs = -1;        // socket write timeout
    // SO_REUSEADDR //重复使用地址

    private int connectTimeoutMs = 10 * 1000; // 建立连接超时时间

    protected SoConfig(String protocol) {
        this.protocol = protocol;
    }

    public static TcpSoConfig TCP() {
        return new TcpSoConfig();
    }

    public static UdpSoConfig UDP() {
        return new UdpSoConfig();
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