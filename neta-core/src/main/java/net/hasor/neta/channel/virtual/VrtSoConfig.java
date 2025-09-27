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
 * virtual options.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtSoConfig extends SoConfig {
    private VrtMode            vrtMode;
    private VrtTransferHandler rcvConvert;

    public VrtSoConfig() {
        super(VrtProvider.NAME);
        this.vrtMode = VrtMode.Default;
        this.rcvConvert = VrtTransfer.duplicate();
    }

    public static VrtSoConfig asDefault() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Default;
        return config;
    }

    public static VrtSoConfig asClient() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Client;
        return config;
    }

    public static VrtSoConfig asServer() {
        VrtSoConfig config = new VrtSoConfig();
        config.vrtMode = VrtMode.Server;
        return config;
    }

    public VrtMode getVrtMode() {
        return this.vrtMode;
    }

    public void setVrtMode(VrtMode vrtMode) {
        this.vrtMode = vrtMode;
    }

    public VrtTransferHandler getRcvConvert() {
        return this.rcvConvert;
    }

    public void setRcvConvert(VrtTransferHandler rcvConvert) {
        this.rcvConvert = rcvConvert;
    }
}