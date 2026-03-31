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
 * Per-channel socket option configuration, including protocol type, buffer sizes, slot counts,
 * suspend flag, and timeout settings.
 * Instances can be created through the static factory methods {@link #TCP()}, {@link #UDP()},
 * and {@link #QUIC()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoConfig {
    // Listener settings.
    private final String  protocol;
    // Queue slot settings.
    private       int     rcvSlotSize = -1;
    private       int     sndSlotSize = -1;
    private       boolean suspend     = false;

    // Socket settings.
    private Integer soRcvBuf         = null; // SO_RCVBUF in bytes.
    private Integer soSndBuf         = null; // SO_SNDBUF in bytes.
    private Integer soReadTimeoutMs  = -1;   // Read-idle timeout in milliseconds.
    private Integer soWriteTimeoutMs = -1;   // Write-idle timeout in milliseconds.
    private int     connectTimeoutMs = 10 * 1000; // Connect timeout in milliseconds.

    /**
     * Create the base socket configuration for the specified protocol.
     * @param protocol protocol name
     */
    protected SoConfig(String protocol) {
        this.protocol = protocol;
    }

    /** Create a TCP-specific configuration. */
    public static TcpSoConfig TCP() {
        return new TcpSoConfig();
    }

    /** Create a UDP-specific configuration. */
    public static UdpSoConfig UDP() {
        return new UdpSoConfig();
    }

    /** Create a QUIC-specific configuration. */
    public static QuicSoConfig QUIC() {
        return new QuicSoConfig();
    }

    /** Return the inbound slot count, where -1 means use the default value. */
    public int getRcvSlotSize() {
        return this.rcvSlotSize;
    }

    /** Set the number of receive buffer slots. */
    public void setRcvSlotSize(int rcvSlotSize) {
        this.rcvSlotSize = rcvSlotSize;
    }

    /** Return the outbound slot count, where -1 means use the default value. */
    public int getSndSlotSize() {
        return this.sndSlotSize;
    }

    /** Set the number of send buffer slots. */
    public void setSndSlotSize(int sndSlotSize) {
        this.sndSlotSize = sndSlotSize;
    }

    /** Return the transport protocol name, for example TCP, UDP, or QUIC. */
    public String getProtocol() {
        return this.protocol;
    }

    /** Return {@code true} if new accept operations are currently suspended. */
    public boolean isSuspend() {
        return this.suspend;
    }

    /** Suspend or resume the listener from accepting new connections. */
    public void setSuspend(boolean suspend) {
        this.suspend = suspend;
    }

    /** Convenience method that sets both SO_RCVBUF and SO_SNDBUF. */
    public void setSoBufSize(int soRcvBuf, int soSndBuf) {
        this.soRcvBuf = soRcvBuf;
        this.soSndBuf = soSndBuf;
    }

    /** Return the SO_RCVBUF hint, or {@code null} to use the operating system default. */
    public Integer getSoRcvBuf() {
        return this.soRcvBuf;
    }

    /** Set the SO_RCVBUF socket option in bytes. */
    public void setSoRcvBuf(Integer soRcvBuf) {
        this.soRcvBuf = soRcvBuf;
    }

    /** Return the SO_SNDBUF hint, or {@code null} to use the operating system default. */
    public Integer getSoSndBuf() {
        return this.soSndBuf;
    }

    /** Set the SO_SNDBUF socket option in bytes. */
    public void setSoSndBuf(Integer soSndBuf) {
        this.soSndBuf = soSndBuf;
    }

    /** Return the read-idle timeout in milliseconds, where -1 disables it. */
    public Integer getSoReadTimeoutMs() {
        return this.soReadTimeoutMs;
    }

    /** Set the read-idle timeout; use -1 to disable it. */
    public void setSoReadTimeoutMs(Integer soReadTimeoutMs) {
        this.soReadTimeoutMs = soReadTimeoutMs;
    }

    /** Return the write-idle timeout in milliseconds, where -1 disables it. */
    public Integer getSoWriteTimeoutMs() {
        return this.soWriteTimeoutMs;
    }

    /** Set the write-idle timeout; use -1 to disable it. */
    public void setSoWriteTimeoutMs(Integer soWriteTimeoutMs) {
        this.soWriteTimeoutMs = soWriteTimeoutMs;
    }

    /** Return the connection establishment timeout in milliseconds. */
    public int getConnectTimeoutMs() {
        return this.connectTimeoutMs;
    }

    /** Set the maximum time to wait for a connection to be established. */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}