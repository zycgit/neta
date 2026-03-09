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
 * <p>Extends the base {@link net.hasor.neta.channel.SoConfig} with TCP-specific tuning
 * knobs.  All properties follow a <em>null-means-unset</em> convention for optional
 * socket options: when a field is {@code null} the corresponding
 * {@code setOption()} call is simply skipped, leaving the OS default in place.
 * <p><b>Swap buffer sizes:</b>
 * <pre>
 *   swapRcvBuf (default 64 KiB) — size of the direct ByteBuffer used as an
 *       intermediate receive buffer by TcpRcvCompletionHandler before copying
 *       incoming bytes into a managed ByteBuf.
 *   swapSndBuf (default 64 KiB) — size of the direct ByteBuffer used by
 *       TcpSndCompletionHandler when flushing a SoSndData to the channel.
 * </pre>
 * These are <em>not</em> the OS-level socket receive/send buffers
 * ({@code SO_RCVBUF} / {@code SO_SNDBUF}); those are inherited from
 * {@link net.hasor.neta.channel.SoConfig}.
 * <p><b>TCP keep-alive settings:</b> Setting {@code soKeepAlive = true} activates
 * {@code SO_KEEPALIVE} on the socket.  On Linux (kernel ≥ 2.4) and macOS the
 * fine-grained timers {@code TCP_KEEPIDLE}, {@code TCP_KEEPINTERVAL}, and
 * {@code TCP_KEEPCOUNT} can also be configured; they are silently ignored on
 * platforms that do not support them at runtime.
 * <table border="1" cellpadding="4">
 *   <tr><th>Field</th><th>Socket option</th><th>Default</th><th>Notes</th></tr>
 *   <tr><td>swapRcvBuf</td><td>n/a</td><td>65536</td><td>internal swap buffer size (bytes)</td></tr>
 *   <tr><td>swapSndBuf</td><td>n/a</td><td>65536</td><td>internal swap buffer size (bytes)</td></tr>
 *   <tr><td>soKeepAlive</td><td>SO_KEEPALIVE</td><td>null (OS default)</td><td>enable TCP keep-alive</td></tr>
 *   <tr><td>soKeepIdleSec</td><td>TCP_KEEPIDLE</td><td>null</td><td>idle seconds before first probe</td></tr>
 *   <tr><td>soKeepIntervalSec</td><td>TCP_KEEPINTERVAL</td><td>null</td><td>seconds between probes</td></tr>
 *   <tr><td>soKeepCount</td><td>TCP_KEEPCOUNT</td><td>null</td><td>max unanswered probes before close</td></tr>
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

    /** Creates a TCP socket config with TCP provider defaults. */
    public TcpSoConfig() {
        super(TcpProvider.NAME);
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

    /** Returns TCP_KEEPIDLE in seconds (idle time before keepalive probes), or null if unset. */
    public Integer getSoKeepIdleSec() {
        return this.soKeepIdleSec;
    }

    /** Sets TCP_KEEPIDLE in seconds. */
    public void setSoKeepIdleSec(Integer soKeepIdleSec) {
        this.soKeepIdleSec = soKeepIdleSec;
    }

    /** Returns TCP_KEEPINTERVAL in seconds (interval between probes), or null if unset. */
    public Integer getSoKeepIntervalSec() {
        return this.soKeepIntervalSec;
    }

    /** Sets TCP_KEEPINTERVAL in seconds. */
    public void setSoKeepIntervalSec(Integer soKeepIntervalSec) {
        this.soKeepIntervalSec = soKeepIntervalSec;
    }

    /** Returns TCP_KEEPCOUNT (max retries before closing inactive connection), or null if unset. */
    public Integer getSoKeepCount() {
        return this.soKeepCount;
    }

    /** Sets TCP_KEEPCOUNT. */
    public void setSoKeepCount(Integer soKeepCount) {
        this.soKeepCount = soKeepCount;
    }

}