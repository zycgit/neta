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
package net.hasor.neta.handler;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.SoResManager;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RCV_UP and RCV_DOWN,SND_UP and SND_DOWN. Is the name of RCV and SND under different endpoints.
 * When the pipeline forms a chain, the rcv event upward propagates,the snd event downward propagates.
 *
 * <p>
 *     When two PipeLayer are connected, the endpoint object is shared in the same direction. e.g., RCV_DOWN and RCV_UP.
 *
 *     For convenience, use the DOWN name
 * </p>
 *
 * <pre>
 *                PipeLayer(0)                    PipeLayer (1)
 *         /------------------------\      /------------------------\
 *         |                        |      |                        |
 *         |             /----------+------+----------\             |
 * DATA -> | RCV_UP      | RCV_DOWN    ->    RCV_UP   |    RCV_DOWN |  -> ...
 *         |             |                            |             |
 * ...  <- | SND_DOWN    | SND_UP      <-    SND_DOWN |      SND_UP |  <- DATA
 *         |             \----------+------+----------/             |
 *         |                        |      |                        |
 *         \------------------------/      \------------------------/
 * </pre>
 *
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeLayerInvocation<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final PipeConfig                                    config;
    private final PipEndpointCreator<?>                         rcvDownEndCreator;
    private final PipEndpointCreator<?>                         sndDownEndCreator;
    private final AtomicBoolean                                 inited;
    //
    private       RCV_DOWN                                      rcvDownEnd;
    private       SND_DOWN                                      sndDownEnd;
    private final PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer;

    interface PipEndpointCreator<T> {
        T createEndpoint(SoResManager resManager, int stackSize);
    }

    public PipeLayerInvocation(PipeConfig config, boolean rcvDownIsBytes, boolean sndDownIsBytes, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
        Objects.requireNonNull(config, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        this.config = config;
        this.rcvDownEndCreator = rcvDownIsBytes ? SoResManager::newByteBuf : (resManager, stackSize) -> new PipeQueue<>(stackSize);
        this.sndDownEndCreator = sndDownIsBytes ? SoResManager::newByteBuf : (resManager, stackSize) -> new PipeQueue<>(stackSize);
        this.inited = new AtomicBoolean();
        this.pipeLayer = pipeLayer;
    }

    /** the {@link PipeLayer} RCV_DOWN to connect the next {@link PipeLayer} RCV_UP. */
    public RCV_DOWN getRcvDown() {
        return this.rcvDownEnd;
    }

    /** the {@link PipeLayer} SND_DOWN to connect the next {@link PipeLayer} SND_UP. */
    public SND_DOWN getSndDown() {
        return this.sndDownEnd;
    }

    public void initLayer(SoResManager rm) {
        if (this.inited.compareAndSet(false, true)) {
            this.rcvDownEnd = (RCV_DOWN) this.rcvDownEndCreator.createEndpoint(rm, this.config.getPipeRcvDownStackSize());
            this.sndDownEnd = (SND_DOWN) this.sndDownEndCreator.createEndpoint(rm, this.config.getPipeSndUpStackSize());
        }
    }

    public PipeStatus doLayer(PipeContext context, boolean isRcv, RCV_UP rcvUp, SND_UP sndUp) throws IOException {
        return this.pipeLayer.doLayer(context, isRcv, rcvUp, this.rcvDownEnd, sndUp, this.sndDownEnd);
    }
}
