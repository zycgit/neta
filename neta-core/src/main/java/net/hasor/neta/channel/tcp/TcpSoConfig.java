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
 * Configuration holder for TCP socket options.
 * <p>This type extends the base {@link net.hasor.neta.channel.SoConfig} with TCP-specific tuning
 * knobs. All optional socket options follow a <em>null means unset</em> convention: when a field is
 * {@code null}, the corresponding {@code setOption()} call is skipped and the system default value
 * is preserved.
 * <p><b>Swap buffer sizes:</b>
 * <pre>
 *   swapRcvBuf (default 64 KiB) - size of the direct ByteBuffer used by
 *       TcpRcvCompletionHandler as the intermediate receive-side swap buffer
 *       before copying data into a managed ByteBuf.
 *   swapSndBuf (default 64 KiB) - size of the direct ByteBuffer used by
 *       TcpSndCompletionHandler when flushing one SoSndData into the channel.
 * </pre>
 * These values are not the operating-system level socket receive/send buffers
 * ({@code SO_RCVBUF} / {@code SO_SNDBUF}); those are inherited from
 * {@link net.hasor.neta.channel.SoConfig}.
 * <p><b>TCP keep-alive settings:</b> when {@code soKeepAlive = true}, {@code SO_KEEPALIVE} is
 * enabled on the socket. On Linux (kernel >= 2.4) and macOS, the more fine-grained timers
 * {@code TCP_KEEPIDLE}, {@code TCP_KEEPINTERVAL}, and {@code TCP_KEEPCOUNT} can also be
 * configured. These options are automatically ignored on platforms that do not support them at
 * runtime.
 * <table border="1" cellpadding="4">
 *   <tr><th>Field</th><th>Socket Option</th><th>Default</th><th>Notes</th></tr>
 *   <tr><td>swapRcvBuf</td><td>n/a</td><td>65536</td><td>internal swap buffer size (bytes)</td></tr>
 *   <tr><td>swapSndBuf</td><td>n/a</td><td>65536</td><td>internal swap buffer size (bytes)</td></tr>
 *   <tr><td>soKeepAlive</td><td>SO_KEEPALIVE</td><td>null (system default)</td><td>enable TCP keep-alive</td></tr>
 *   <tr><td>soKeepIdleSec</td><td>TCP_KEEPIDLE</td><td>null</td><td>idle seconds before the first probe</td></tr>
 *   <tr><td>soKeepIntervalSec</td><td>TCP_KEEPINTERVAL</td><td>null</td><td>seconds between probes</td></tr>
 *   <tr><td>soKeepCount</td><td>TCP_KEEPCOUNT</td><td>null</td><td>maximum unanswered probes before close</td></tr>
 * </table>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see TcpSoConfigUtils
 * @see net.hasor.neta.channel.SoConfig
 */
public class TcpSoConfig extends SoConfig {
    // Swap buffer sizes.
    private int swapRcvBuf = 64 * 1024;
    private int swapSndBuf = 64 * 1024;

    private Boolean soKeepAlive       = null; // SO_KEEPALIVE.
    private Integer soKeepIdleSec     = null; // TCP_KEEPIDLE in seconds.
    private Integer soKeepIntervalSec = null; // TCP_KEEPINTERVAL in seconds.
    private Integer soKeepCount       = null; // TCP_KEEPCOUNT.

    /**
     * Create a TCP socket configuration with the provider defaults.
     */
    public TcpSoConfig() {
        super(TcpProvider.NAME);
    }

    /**
     * Return the swap buffer size for receiving data, in bytes.
     * @return the receive swap buffer size
     */
    public int getSwapRcvBuf() {
        return this.swapRcvBuf;
    }

    /**
     * Set the swap buffer size for receiving data, in bytes.
     * @param swapRcvBuf the receive swap buffer size
     */
    public void setSwapRcvBuf(int swapRcvBuf) {
        this.swapRcvBuf = swapRcvBuf;
    }

    /**
     * Return the swap buffer size for sending data, in bytes.
     * @return the send swap buffer size
     */
    public int getSwapSndBuf() {
        return this.swapSndBuf;
    }

    /**
     * Set the swap buffer size for sending data, in bytes.
     * @param swapSndBuf the send swap buffer size
     */
    public void setSwapSndBuf(int swapSndBuf) {
        this.swapSndBuf = swapSndBuf;
    }

    /**
     * Return the SO_KEEPALIVE setting.
     * @return the SO_KEEPALIVE setting, or null if unset
     */
    public Boolean getSoKeepAlive() {
        return this.soKeepAlive;
    }

    /**
     * Enable or disable SO_KEEPALIVE.
     * @param soKeepAlive whether keep-alive should be enabled
     */
    public void setSoKeepAlive(Boolean soKeepAlive) {
        this.soKeepAlive = soKeepAlive;
    }

    /**
     * Return the TCP_KEEPIDLE setting, in seconds.
     * @return the TCP_KEEPIDLE setting, or null if unset
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
     * Return the TCP_KEEPINTERVAL setting, in seconds.
     * @return the TCP_KEEPINTERVAL setting, or null if unset
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
     * Return the TCP_KEEPCOUNT setting.
     * @return the TCP_KEEPCOUNT setting, or null if unset
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

}