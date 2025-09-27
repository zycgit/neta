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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.handler.PlayLoad;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class VrtAsyncChannel implements AsyncChannel {
    private static final Logger                logger = Logger.getLogger(VrtAsyncChannel.class);
    private final        long                  channelId;
    private final        VrtSocketAddress      bindAddr;
    private final        VrtSocketAddress      targetAddr;
    private final        VrtAsyncServerChannel target;
    private final        SoContextService      context;
    private final        VrtSoConfig           soConfig;
    private final        AtomicBoolean         closeFlag;

    VrtAsyncChannel(long channelId, VrtAsyncServerChannel target, SoContext context, SocketAddress targetAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.bindAddr = new VrtSocketAddress(((VrtSocketAddress) targetAddr).getAddress(), target != null);
        this.targetAddr = (VrtSocketAddress) targetAddr;
        this.target = target;
        this.context = (SoContextService) context;
        this.soConfig = (VrtSoConfig) soConfig;
        this.closeFlag = new AtomicBoolean(false);
    }

    @Override
    public VrtSoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public VrtSocketAddress getLocalAddress() {
        return this.bindAddr;
    }

    @Override
    public VrtSocketAddress getRemoteAddress() {
        return this.targetAddr;
    }

    @Override
    public boolean isOpen() {
        return !this.closeFlag.get();
    }

    @Override
    public void close() throws IOException {
        this.closeFlag.set(true);
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        try {
            // create channel
            VrtChannel channel;
            if (this.target != null) {
                VrtMode useMode = VrtMode.Client;
                channel = new VrtChannel(this.channelId, new NetMonitor(), null, useMode, initializer, this, this.context);
                this.target.acceptLink(channel);
            } else {
                VrtMode useMode = this.soConfig.getVrtMode();
                channel = new VrtChannel(this.channelId, new NetMonitor(), null, useMode, initializer, this, this.context);
            }

            // init
            this.context.initChannel(channel, true);
            future.completed(channel);
        } catch (Throwable e) {
            logger.error("ERROR: ConnectFailed, " + e.getMessage(), e);
            future.failed(e);
        }
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        VrtChannel vrtChannel = (VrtChannel) channel;
        while (!wContext.isEmpty()) {
            SoSndData sndData = wContext.popData();

            if (!this.isOpen()) {
                sndData.failed(SoCloseException.INSTANCE);
                continue;
            }

            while (sndData.hasReadable()) {
                Object data = sndData.transferTake();
                PlayLoad playLoad = PlayLoad.of(vrtChannel, data, false, true);
                this.context.trigger(playLoad);
            }

            sndData.completed();
        }
    }
}
