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
package net.hasor.neta.channel.udp;
import net.hasor.neta.channel.SoConfig;

/**
 * UDP specific configuration options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class UdpSoConfig extends SoConfig {
    private Integer rcvPacketSize;
    private boolean rcvRemoteOnly           = true;
    // write-timeout retry (channel-level)
    private int     sndWriteRetryCount      = 0;   // 0 = no retry; N = retry up to N times on write timeout
    private int     sndWriteRetryIntervalMs = 50;  // delay between retries in milliseconds
    //SO_REUSEADDR	重复使用地址
    //SO_BROADCAST	允许传输广播数据报
    //IP_TOS	互联网协议 (IP) 标头中的服务类型 (ToS) 八位字节
    //IP_MULTICAST_IF	网际协议 (IP) 多播数据报的网络接口
    //IP_MULTICAST_TTL	time-to-live 用于 Internet 协议 (IP) 多播数据报
    //IP_MULTICAST_LOOP	互联网协议 (IP) 多播数据报的环回

    public UdpSoConfig() {
        super(UdpProvider.NAME);
    }

    /** Protected constructor for subclasses that use a different provider name. */
    protected UdpSoConfig(String providerName) {
        super(providerName);
    }

    /** Returns the maximum receive packet size in bytes, or null to use the socket buffer size. */
    public Integer getRcvPacketSize() {
        return this.rcvPacketSize;
    }

    /** Sets the maximum receive packet size in bytes. */
    public void setRcvPacketSize(Integer rcvPacketSize) {
        this.rcvPacketSize = rcvPacketSize;
    }

    /** Returns true if receive is restricted to the connected remote address only. */
    public boolean isRcvRemoteOnly() {
        return this.rcvRemoteOnly;
    }

    /** Sets whether receive is restricted to the connected remote address only. */
    public void setRcvRemoteOnly(boolean rcvRemoteOnly) {
        this.rcvRemoteOnly = rcvRemoteOnly;
    }

    /** Returns the number of write-timeout retries (0 = no retry). */
    public int getSndWriteRetryCount() {
        return this.sndWriteRetryCount;
    }

    /** Sets the number of write-timeout retries. */
    public void setSndWriteRetryCount(int sndWriteRetryCount) {
        this.sndWriteRetryCount = sndWriteRetryCount;
    }

    /** Returns the delay between write retries in milliseconds. */
    public int getSndWriteRetryIntervalMs() {
        return this.sndWriteRetryIntervalMs;
    }

    /** Sets the delay between write retries in milliseconds. */
    public void setSndWriteRetryIntervalMs(int sndWriteRetryIntervalMs) {
        this.sndWriteRetryIntervalMs = sndWriteRetryIntervalMs;
    }
}