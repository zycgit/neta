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
 * Configuration object for UDP and UDP-based transports.
 * <p>The settings here control three things in the current implementation:
 * <ul>
 *   <li>the size of the per-transport receive packet buffer via {@code rcvPacketSize};</li>
 *   <li>whether {@link UdpAsyncClientChannel} should ignore datagrams from senders other
 *       than the configured remote peer via {@code rcvRemoteOnly};</li>
 *   <li>retry policy used by {@link AbstractUdpWriteTask} when a datagram send cannot
 *       make progress immediately.</li>
 * </ul>
 * <p>The base {@link SoConfig} buffer fields still map to OS socket options through
 * {@link UdpSoConfigUtils}. The logical receive-packet size resolved by
 * {@link UdpSoConfigUtils#getRcvPacketSize(UdpSoConfig)} determines the capacity of the
 * shared receive buffer used by {@link UdpTransport}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see UdpSoConfigUtils
 * @see net.hasor.neta.channel.SoConfig
 */
public class UdpSoConfig extends SoConfig {
    private Integer rcvPacketSize;
    private boolean rcvRemoteOnly           = true;
    // Channel-level write timeout retry settings.
    private int     sndWriteRetryCount      = 0;   // 0 = disabled.
    private int     sndWriteRetryIntervalMs = 50;  // Delay between retries in milliseconds.

    /** Creates a UDP socket config with UDP provider defaults. */
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