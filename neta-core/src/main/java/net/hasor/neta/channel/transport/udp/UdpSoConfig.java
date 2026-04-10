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
package net.hasor.neta.channel.transport.udp;
import net.hasor.neta.channel.SoConfig;

/**
 * Configuration object for UDP and UDP-based transports.
 * <p>In the current implementation, these settings mainly control three aspects:
 * <ul>
 *   <li>the size of the receive packet buffer per transport instance via {@code rcvPacketSize}</li>
 *   <li>whether {@link UdpAsyncClientChannel} ignores datagrams that do not come from the
 *       configured remote peer via {@code rcvRemoteOnly}</li>
 *   <li>the retry policy used by {@link AbstractUdpWriteTask} when one datagram send cannot make
 *       progress immediately</li>
 * </ul>
 * <p>The buffer fields inherited from {@link SoConfig} are still mapped to operating-system socket
 * options through {@link UdpSoConfigUtils}. The logical receive-packet size resolved by
 * {@link UdpSoConfigUtils#getRcvPacketSize(UdpSoConfig)} determines the capacity of the shared
 * receive buffer used by {@link UdpTransport}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see UdpSoConfigUtils
 * @see net.hasor.neta.channel.SoConfig
 */
public class UdpSoConfig extends SoConfig {
    private Integer rcvPacketSize;
    private boolean rcvRemoteOnly           = true;
    // Channel-level write-timeout retry configuration.
    private int     sndWriteRetryCount      = 0;   // 0 means disabled.
    private int     sndWriteRetryIntervalMs = 50;  // Delay between retries in milliseconds.

    /**
     * Create a configuration object with the UDP provider defaults.
     */
    public UdpSoConfig() {
        super(UdpProvider.NAME);
    }

    /**
     * Protected constructor for subclasses.
     * @param providerName the provider name
     */
    protected UdpSoConfig(String providerName) {
        super(providerName);
    }

    /**
     * Return the maximum receive packet size in bytes.
     * @return the receive packet size, or null to fall back to the socket receive buffer size
     */
    public Integer getRcvPacketSize() {
        return this.rcvPacketSize;
    }

    /**
     * Set the maximum receive packet size in bytes.
     * @param rcvPacketSize the receive packet size
     */
    public void setRcvPacketSize(Integer rcvPacketSize) {
        this.rcvPacketSize = rcvPacketSize;
    }

    /**
     * Return whether the receive side accepts datagrams only from the connected remote address.
     * @return true when only datagrams from the configured remote address are accepted
     */
    public boolean isRcvRemoteOnly() {
        return this.rcvRemoteOnly;
    }

    /**
     * Set whether the receive side accepts datagrams only from the connected remote address.
     * @param rcvRemoteOnly whether only datagrams from the configured remote address are accepted
     */
    public void setRcvRemoteOnly(boolean rcvRemoteOnly) {
        this.rcvRemoteOnly = rcvRemoteOnly;
    }

    /**
     * Return the retry count after a write timeout.
     * @return the retry count, where 0 means no retry
     */
    public int getSndWriteRetryCount() {
        return this.sndWriteRetryCount;
    }

    /**
     * Set the retry count after a write timeout.
     * @param sndWriteRetryCount the retry count
     */
    public void setSndWriteRetryCount(int sndWriteRetryCount) {
        this.sndWriteRetryCount = sndWriteRetryCount;
    }

    /**
     * Return the interval between two write retries in milliseconds.
     * @return the retry interval
     */
    public int getSndWriteRetryIntervalMs() {
        return this.sndWriteRetryIntervalMs;
    }

    /**
     * Set the interval between two write retries in milliseconds.
     * @param sndWriteRetryIntervalMs the retry interval
     */
    public void setSndWriteRetryIntervalMs(int sndWriteRetryIntervalMs) {
        this.sndWriteRetryIntervalMs = sndWriteRetryIntervalMs;
    }
}