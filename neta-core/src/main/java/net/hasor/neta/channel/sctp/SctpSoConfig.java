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
 * Configuration object for Neta's SCTP transport.
 * <p>This type currently contributes three groups of settings to the SCTP code path:
 * <ul>
 *   <li>message swap-buffer sizes used by the framework-side receive/send staging buffers;</li>
 *   <li>retry policy for {@link SctpWriteTask} when {@code send()} cannot make progress;</li>
 *   <li>additional keepalive-related values carried as configuration fields for future
 *       or platform-specific socket-option application.</li>
 * </ul>
 * <p>In the current implementation only a subset of these fields is actively consumed:
 * {@code swapRcvBuf} determines the size of the receive staging buffer,
 * {@code sndWriteRetryCount} and {@code sndWriteRetryIntervalMs} are used by
 * {@link SctpWriteTask}, while the keepalive fields are stored here but are not
 * applied by {@link SctpSoConfigUtils#configListen(SctpSoConfig, com.sun.nio.sctp.SctpServerChannel)} yet.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SctpSoConfigUtils
 * @see net.hasor.neta.channel.SoConfig
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

    /** Creates an SCTP socket config with SCTP provider defaults. */
    public SctpSoConfig() {
        super(SctpProvider.NAME);
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

    /** Returns TCP_KEEPIDLE in seconds, or null if unset. */
    public Integer getSoKeepIdleSec() {
        return this.soKeepIdleSec;
    }

    /** Sets TCP_KEEPIDLE in seconds. */
    public void setSoKeepIdleSec(Integer soKeepIdleSec) {
        this.soKeepIdleSec = soKeepIdleSec;
    }

    /** Returns TCP_KEEPINTERVAL in seconds, or null if unset. */
    public Integer getSoKeepIntervalSec() {
        return this.soKeepIntervalSec;
    }

    /** Sets TCP_KEEPINTERVAL in seconds. */
    public void setSoKeepIntervalSec(Integer soKeepIntervalSec) {
        this.soKeepIntervalSec = soKeepIntervalSec;
    }

    /** Returns TCP_KEEPCOUNT, or null if unset. */
    public Integer getSoKeepCount() {
        return this.soKeepCount;
    }

    /** Sets TCP_KEEPCOUNT. */
    public void setSoKeepCount(Integer soKeepCount) {
        this.soKeepCount = soKeepCount;
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