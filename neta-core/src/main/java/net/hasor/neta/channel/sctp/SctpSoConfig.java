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
package net.hasor.neta.channel.sctp;
import net.hasor.neta.channel.SoConfig;

/**
 * Configuration object used by the SCTP transport layer in Neta.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SctpSoConfig extends SoConfig {
    // Swap buffer sizes.
    private int swapRcvBuf              = 64 * 1024;
    private int swapSndBuf              = 64 * 1024;
    // Channel-level write timeout retry settings.
    private int sndWriteRetryCount      = 0;   // 0 = disabled.
    private int sndWriteRetryIntervalMs = 50;  // Delay between retries in milliseconds.

    private Boolean soKeepAlive       = null; // SO_KEEPALIVE.
    private Integer soKeepIdleSec     = null; // TCP_KEEPIDLE in seconds.
    private Integer soKeepIntervalSec = null; // TCP_KEEPINTERVAL in seconds.
    private Integer soKeepCount       = null; // TCP_KEEPCOUNT.

    /**
     * Create a configuration object initialized with the SCTP provider defaults.
     */
    public SctpSoConfig() {
        super(SctpProvider.NAME);
    }

    /**
     * Return the size of the receive-side swap buffer, in bytes.
     * @return the receive swap buffer size
     */
    public int getSwapRcvBuf() {
        return this.swapRcvBuf;
    }

    /**
     * Set the size of the receive-side swap buffer, in bytes.
     * @param swapRcvBuf the receive swap buffer size
     */
    public void setSwapRcvBuf(int swapRcvBuf) {
        this.swapRcvBuf = swapRcvBuf;
    }

    /**
     * Return the size of the send-side swap buffer, in bytes.
     * @return the send swap buffer size
     */
    public int getSwapSndBuf() {
        return this.swapSndBuf;
    }

    /**
     * Set the size of the send-side swap buffer, in bytes.
     * @param swapSndBuf the send swap buffer size
     */
    public void setSwapSndBuf(int swapSndBuf) {
        this.swapSndBuf = swapSndBuf;
    }

    /**
     * Return the configured SO_KEEPALIVE value.
     * @return the SO_KEEPALIVE setting, or null if it has not been configured
     */
    public Boolean getSoKeepAlive() {
        return this.soKeepAlive;
    }

    /**
     * Set whether SO_KEEPALIVE should be enabled.
     * @param soKeepAlive whether keepalive should be enabled
     */
    public void setSoKeepAlive(Boolean soKeepAlive) {
        this.soKeepAlive = soKeepAlive;
    }

    /**
     * Return the configured TCP_KEEPIDLE value, in seconds.
     * @return the TCP_KEEPIDLE setting, or null if it has not been configured
     */
    public Integer getSoKeepIdleSec() {
        return this.soKeepIdleSec;
    }

    /**
     * Set TCP_KEEPIDLE, in seconds.
     * @param soKeepIdleSec the idle probe delay
     */
    public void setSoKeepIdleSec(Integer soKeepIdleSec) {
        this.soKeepIdleSec = soKeepIdleSec;
    }

    /**
     * Return the configured TCP_KEEPINTERVAL value, in seconds.
     * @return the TCP_KEEPINTERVAL setting, or null if it has not been configured
     */
    public Integer getSoKeepIntervalSec() {
        return this.soKeepIntervalSec;
    }

    /**
     * Set TCP_KEEPINTERVAL, in seconds.
     * @param soKeepIntervalSec the probe interval
     */
    public void setSoKeepIntervalSec(Integer soKeepIntervalSec) {
        this.soKeepIntervalSec = soKeepIntervalSec;
    }

    /**
     * Return the configured TCP_KEEPCOUNT value.
     * @return the TCP_KEEPCOUNT setting, or null if it has not been configured
     */
    public Integer getSoKeepCount() {
        return this.soKeepCount;
    }

    /**
     * Set TCP_KEEPCOUNT.
     * @param soKeepCount the probe count
     */
    public void setSoKeepCount(Integer soKeepCount) {
        this.soKeepCount = soKeepCount;
    }

    /**
     * Return the retry count used after a send timeout.
     * @return the retry count; 0 means no retry
     */
    public int getSndWriteRetryCount() {
        return this.sndWriteRetryCount;
    }

    /**
     * Set the retry count used after a send timeout.
     * @param sndWriteRetryCount the retry count
     */
    public void setSndWriteRetryCount(int sndWriteRetryCount) {
        this.sndWriteRetryCount = sndWriteRetryCount;
    }

    /**
     * Return the wait time between two send retries, in milliseconds.
     * @return the retry interval
     */
    public int getSndWriteRetryIntervalMs() {
        return this.sndWriteRetryIntervalMs;
    }

    /**
     * Set the wait time between two send retries, in milliseconds.
     * @param sndWriteRetryIntervalMs the retry interval
     */
    public void setSndWriteRetryIntervalMs(int sndWriteRetryIntervalMs) {
        this.sndWriteRetryIntervalMs = sndWriteRetryIntervalMs;
    }

}