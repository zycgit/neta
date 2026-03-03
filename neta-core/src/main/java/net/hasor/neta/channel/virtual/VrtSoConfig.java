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
 * Virtual specific configuration options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
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