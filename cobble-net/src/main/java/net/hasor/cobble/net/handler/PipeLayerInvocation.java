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
package net.hasor.cobble.net.handler;
import net.hasor.cobble.net.channel.PipeContext;
import net.hasor.cobble.net.channel.SoResManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PipeLayer invoker
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeLayerInvocation<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final PipeConfig                                    config;
    private final PipEndpointCreator<?>                         rcvDownEndCreator;
    private final PipEndpointCreator<?>                         sndUpEndCreator;
    private final AtomicBoolean                                 inited;
    //
    private       RCV_DOWN                                      rcvDownEnd;
    private       SND_UP                                        sndUpEnd;
    private final PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer;

    interface PipEndpointCreator<T> {
        T createEndpoint(SoResManager resManager, int stackSize);
    }

    public PipeLayerInvocation(PipeConfig config, boolean rcvDownIsBytes, boolean sndUpIsBytes, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
        Objects.requireNonNull(config, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        this.config = config;
        this.rcvDownEndCreator = rcvDownIsBytes ? SoResManager::newByteBuf : (resManager, stackSize) -> new PipeQueue<>(stackSize);
        this.sndUpEndCreator = sndUpIsBytes ? SoResManager::newByteBuf : (resManager, stackSize) -> new PipeQueue<>(stackSize);
        this.inited = new AtomicBoolean();
        this.pipeLayer = pipeLayer;
    }

    /** this {@link PipeLayer} RCV_DOWN to connect the next {@link PipeLayer} RCV_UP. */
    public RCV_DOWN getRcvDown() {
        return this.rcvDownEnd;
    }

    /** this {@link PipeLayer} SND_UP to connect the next {@link PipeLayer} SND_DOWN. */
    public SND_UP getSndUp() {
        return this.sndUpEnd;
    }

    private void init(SoResManager rm) {
        this.rcvDownEnd = (RCV_DOWN) this.rcvDownEndCreator.createEndpoint(rm, this.config.getPipeRcvDownStackSize());
        this.sndUpEnd = (SND_UP) this.sndUpEndCreator.createEndpoint(rm, this.config.getPipeRcvDownStackSize());
    }

    public PipeStatus doLayer(PipeContext context, boolean isRcv, RCV_UP rcvUp, SND_DOWN sndDown) throws IOException {
        if (this.inited.compareAndSet(false, true)) {
            this.init(context.getSoResManager());
        }
        return this.pipeLayer.doLayer(context, isRcv, rcvUp, this.rcvDownEnd, this.sndUpEnd, sndDown);
    }
}
