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
package net.hasor.neta.channel.transport.virtual;
import net.hasor.neta.channel.SoConfig;
/**
 * Configuration object for the in-process virtual transport.
 * <p>Virtual channels simulate the full transport process inside one JVM. This configuration
 * controls how links are created and how {@link VrtTransfer} delivers data.
 * <p><b>Field overview:</b>
 * <table border="1" cellpadding="4">
 *   <tr><th>Field</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>vrtMode</td><td>{@link VrtMode#Default}</td>
 *       <td>Indicates which role should be applied to channel objects created around this transport.
 *           Connect-mode clients must start with {@code Default}; accepted server-side channels
 *           are materialized later as {@code Server}.</td></tr>
 *   <tr><td>rcvConvert</td><td>{@link VrtTransfer#duplicate()}</td>
 *       <td>The receive-side converter used by each {@link VrtTransferLink}.
 *           The default strategy duplicates {@link net.hasor.neta.bytebuf.ByteBuf} payloads so
 *           multiple peers do not share mutable buffer state.</td></tr>
 *   <tr><td>asynchronous</td><td>true</td>
 *       <td>Controls whether {@link VrtTransfer} dispatches deliveries asynchronously through the
 *           manager executor or inline on the publisher thread.</td></tr>
 *   <tr><td>batchSize</td><td>1</td>
 *       <td>The minimum number of queued messages a link must accumulate before flushing data into
 *           the target channel.</td></tr>
 *   <tr><td>lossRate</td><td>0</td>
 *       <td>The threshold passed to {@link VrtTransfer#setLossRate(int)}.
 *           In the current implementation, {@code 0} means no packet loss, and larger values make
 *           payloads less likely to be skipped.</td></tr>
 * </table>
 * <p>The static factories {@link #asDefault()}, {@link #asClient()}, and {@link #asServer()} offer
 * common presets without changing any other field.
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

    /**
     * Create a default virtual transport configuration object.
     */
    public VrtSoConfig() {
        super(VrtProvider.NAME);
        this.vrtMode = VrtMode.Default;
        this.rcvConvert = VrtTransfer.duplicate();
        this.asynchronous = true;
        this.batchSize = 1;
        this.lossRate = 0;
    }

    /**
     * Create a configuration object using {@link VrtMode#Default} mode.
     * @return the configuration object
     */
    public static VrtSoConfig asDefault() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Default;
        return config;
    }

    /**
     * Create a configuration object using {@link VrtMode#Client} mode.
     * @return the configuration object
     */
    public static VrtSoConfig asClient() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Client;
        return config;
    }

    /**
     * Create a configuration object using {@link VrtMode#Server} mode.
     * @return the configuration object
     */
    public static VrtSoConfig asServer() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Server;
        return config;
    }

    /**
     * Return the virtual channel mode.
     * @return the virtual channel mode
     */
    public VrtMode getVrtMode() {
        return this.vrtMode;
    }

    /**
     * Set the virtual channel mode.
     * @param vrtMode the virtual channel mode
     */
    public void setVrtMode(VrtMode vrtMode) {
        this.vrtMode = vrtMode;
    }

    /**
     * Return the data conversion handler used on the receive side of a virtual link.
     * @return the receive-side conversion handler
     */
    public VrtTransferHandler getRcvConvert() {
        return this.rcvConvert;
    }

    /**
     * Set the receive-side data conversion handler.
     * @param rcvConvert the receive-side conversion handler
     */
    public void setRcvConvert(VrtTransferHandler rcvConvert) {
        this.rcvConvert = rcvConvert;
    }

    /**
     * Determine whether virtual data transfer uses asynchronous task submission.
     * @return true if asynchronous delivery is enabled
     */
    public boolean isAsynchronous() {
        return asynchronous;
    }

    /**
     * Set whether virtual data transfer should use asynchronous delivery.
     * @param asynchronous whether delivery is asynchronous
     */
    public void setAsynchronous(boolean asynchronous) {
        this.asynchronous = asynchronous;
    }

    /**
     * Return the batch size used by virtual transfer.
     * @return the batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Set the batch size used by virtual transfer.
     * @param batchSize the batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Return the simulated packet loss rate, in the range 0 to 100.
     * @return the packet loss rate
     */
    public int getLossRate() {
        return lossRate;
    }

    /**
     * Set the simulated packet loss rate, in the range 0 to 100.
     * @param lossRate the packet loss rate
     */
    public void setLossRate(int lossRate) {
        this.lossRate = lossRate;
    }
}