/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel;
import net.hasor.neta.channel.quic.QuicSoConfig;
import net.hasor.neta.channel.tcp.TcpSoConfig;
import net.hasor.neta.channel.udp.UdpSoConfig;

/**
 * Per-channel socket options: protocol type, buffer sizes, slot counts, suspend flag and timeouts.
 * Use the static factories {@link #TCP()}, {@link #UDP()}, or {@link #QUIC()} to create instances.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoConfig {
    // for Listener
    private final String  protocol;
    // for slot size
    private       int     rcvSlotSize = -1;
    private       int     sndSlotSize = -1;
    private       boolean suspend     = false;

    // for Socket
    private Integer soRcvBuf         = null; // SO_RCVBUF: The size of the socket receive buffer
    private Integer soSndBuf         = null; // SO_SNDBUF: The size of the socket send buffer
    private Integer soReadTimeoutMs  = -1;        // socket read timeout
    private Integer soWriteTimeoutMs = -1;        // socket write timeout
    // SO_REUSEADDR //重复使用地址

    private int connectTimeoutMs = 10 * 1000; // 建立连接超时时间

    protected SoConfig(String protocol) {
        this.protocol = protocol;
    }

    /** Creates a TCP-specific config. */
    public static TcpSoConfig TCP() {
        return new TcpSoConfig();
    }

    /** Creates a UDP-specific config. */
    public static UdpSoConfig UDP() {
        return new UdpSoConfig();
    }

    /** Creates a QUIC-specific config. */
    public static QuicSoConfig QUIC() {
        return new QuicSoConfig();
    }

    /** Returns the receive-side slot count (-1 = default). */
    public int getRcvSlotSize() {
        return this.rcvSlotSize;
    }

    /** Sets the number of receive buffer slots. */
    public void setRcvSlotSize(int rcvSlotSize) {
        this.rcvSlotSize = rcvSlotSize;
    }

    /** Returns the send-side slot count (-1 = default). */
    public int getSndSlotSize() {
        return this.sndSlotSize;
    }

    /** Sets the number of send buffer slots. */
    public void setSndSlotSize(int sndSlotSize) {
        this.sndSlotSize = sndSlotSize;
    }

    /** Returns the transport protocol name (e.g. "TCP", "UDP", "QUIC"). */
    public String getProtocol() {
        return this.protocol;
    }

    /** Returns {@code true} if new accept operations are suspended (paused). */
    public boolean isSuspend() {
        return this.suspend;
    }

    /** Suspends or resumes the listener from accepting new connections. */
    public void setSuspend(boolean suspend) {
        this.suspend = suspend;
    }

    /** Convenience method to set both SO_RCVBUF and SO_SNDBUF to the same values. */
    public void setSoBufSize(int soRcvBuf, int soSndBuf) {
        this.soRcvBuf = soRcvBuf;
        this.soSndBuf = soSndBuf;
    }

    /** Returns the SO_RCVBUF hint, or {@code null} to use the OS default. */
    public Integer getSoRcvBuf() {
        return this.soRcvBuf;
    }

    /** Sets the SO_RCVBUF socket option (bytes). */
    public void setSoRcvBuf(Integer soRcvBuf) {
        this.soRcvBuf = soRcvBuf;
    }

    /** Returns the SO_SNDBUF hint, or {@code null} to use the OS default. */
    public Integer getSoSndBuf() {
        return this.soSndBuf;
    }

    /** Sets the SO_SNDBUF socket option (bytes). */
    public void setSoSndBuf(Integer soSndBuf) {
        this.soSndBuf = soSndBuf;
    }

    /** Returns the read-idle timeout in milliseconds (-1 = disabled). */
    public Integer getSoReadTimeoutMs() {
        return this.soReadTimeoutMs;
    }

    /** Sets the read-idle timeout; -1 disables the timeout. */
    public void setSoReadTimeoutMs(Integer soReadTimeoutMs) {
        this.soReadTimeoutMs = soReadTimeoutMs;
    }

    /** Returns the write-idle timeout in milliseconds (-1 = disabled). */
    public Integer getSoWriteTimeoutMs() {
        return this.soWriteTimeoutMs;
    }

    /** Sets the write-idle timeout; -1 disables the timeout. */
    public void setSoWriteTimeoutMs(Integer soWriteTimeoutMs) {
        this.soWriteTimeoutMs = soWriteTimeoutMs;
    }

    /** Returns the connection establishment timeout in milliseconds. */
    public int getConnectTimeoutMs() {
        return this.connectTimeoutMs;
    }

    /** Sets the maximum time to wait for a connection to be established. */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}