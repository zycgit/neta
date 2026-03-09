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
package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.SoConfig;

/**
 * Configuration holder for the virtual in-process transport.
 * <p>Virtual channels simulate a transport entirely inside one JVM. This config
 * controls how the link is created and how {@link VrtTransfer} delivers data.
 * <p><b>Fields overview:</b>
 * <table border="1" cellpadding="4">
 *   <tr><th>Field</th><th>Default</th><th>Notes</th></tr>
 *   <tr><td>vrtMode</td><td>{@link VrtMode#Default}</td>
 *       <td>Requested role hint for the channel objects created around the transport.
 *           Connect-mode clients must start with {@code Default}; accepted server-side
 *           channels are materialized later as {@code Server}.</td></tr>
 *   <tr><td>rcvConvert</td><td>{@link VrtTransfer#duplicate()}</td>
 *       <td>Receive-side converter used by each {@link VrtTransferLink}. The default
 *           strategy duplicates {@link net.hasor.neta.bytebuf.ByteBuf} payloads so peers
 *           do not share mutable buffer state.</td></tr>
 *   <tr><td>asynchronous</td><td>true</td>
 *       <td>Controls whether {@link VrtTransfer} dispatches each target delivery through
 *           the manager executor or runs inline on the publisher thread.</td></tr>
 *   <tr><td>batchSize</td><td>1</td>
 *       <td>Minimum queued payload count required before a link flushes data into the
 *           target channel.</td></tr>
 *   <tr><td>lossRate</td><td>0</td>
 *       <td>Threshold value forwarded to {@link VrtTransfer#setLossRate(int)}. In the
 *           current implementation {@code 0} disables dropping and larger values reduce
 *           the chance that a payload is skipped.</td></tr>
 * </table>
 * <p>Static factories {@link #asDefault()}, {@link #asClient()}, and
 * {@link #asServer()} provide common presets without changing any other field.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see VrtMode
 * @see VrtTransfer
 */
public class VrtSoConfig extends SoConfig {
    private VrtMode            vrtMode;
    private VrtTransferHandler rcvConvert;
    private boolean            asynchronous;
    private int                batchSize;
    private int                lossRate;

    public VrtSoConfig() {
        super(VrtProvider.NAME);
        this.vrtMode = VrtMode.Default;
        this.rcvConvert = VrtTransfer.duplicate();
        this.asynchronous = true;
        this.batchSize = 1;
        this.lossRate = 0;
    }

    /** Creates a config with {@link VrtMode#Default} mode. */
    public static VrtSoConfig asDefault() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Default;
        return config;
    }

    /** Creates a config with {@link VrtMode#Client} mode. */
    public static VrtSoConfig asClient() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Client;
        return config;
    }

    /** Creates a config with {@link VrtMode#Server} mode. */
    public static VrtSoConfig asServer() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Server;
        return config;
    }

    /** Returns the virtual channel mode (Default, Client, or Server). */
    public VrtMode getVrtMode() {
        return this.vrtMode;
    }

    /** Sets the virtual channel mode. */
    public void setVrtMode(VrtMode vrtMode) {
        this.vrtMode = vrtMode;
    }

    /** Returns the handler that converts data on receive side of a virtual link. */
    public VrtTransferHandler getRcvConvert() {
        return this.rcvConvert;
    }

    /** Sets the receive-side data conversion handler. */
    public void setRcvConvert(VrtTransferHandler rcvConvert) {
        this.rcvConvert = rcvConvert;
    }

    /** Returns true if virtual data transfer uses async task submission. */
    public boolean isAsynchronous() {
        return asynchronous;
    }

    /** Sets whether virtual data transfer is asynchronous. */
    public void setAsynchronous(boolean asynchronous) {
        this.asynchronous = asynchronous;
    }

    /** Returns the batch size for virtual transfer (messages accumulated before delivery). */
    public int getBatchSize() {
        return batchSize;
    }

    /** Sets the batch size for virtual transfer. */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /** Returns the simulated packet loss rate (0–100 percent). */
    public int getLossRate() {
        return lossRate;
    }

    /** Sets the simulated packet loss rate (0–100 percent). */
    public void setLossRate(int lossRate) {
        this.lossRate = lossRate;
    }
}