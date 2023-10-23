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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.PipeContext;

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
    private static final Logger                                        logger = Logger.getLogger(PipeLayerInvocation.class);
    private final        String                                        name;
    private final        PipeConfig                                    config;
    private final        AtomicBoolean                                 inited;
    //
    private              PipeQueue<RCV_DOWN>                           rcvDownEnd;
    private              PipeQueue<SND_DOWN>                           sndDownEnd;
    private final        PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer;

    public PipeLayerInvocation(String name, PipeConfig config, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
        Objects.requireNonNull(config, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        this.name = name;
        this.config = config;
        this.inited = new AtomicBoolean();
        this.pipeLayer = pipeLayer;
    }

    /** the {@link PipeLayer} RCV_DOWN to connect the next {@link PipeLayer} RCV_UP. */
    public PipeQueue<RCV_DOWN> getRcvDown() {
        return this.rcvDownEnd;
    }

    /** the {@link PipeLayer} SND_DOWN to connect the next {@link PipeLayer} SND_UP. */
    public PipeQueue<SND_DOWN> getSndDown() {
        return this.sndDownEnd;
    }

    @Override
    public String toString() {
        return "PipeLayer [name=" + this.name + ", queue=" + this.rcvDownEnd.queueSize() + ", slot=" + this.sndDownEnd.slotSize() + "]";
    }

    public void initLayer(PipeContext pipeContext) throws Exception {
        if (this.inited.compareAndSet(false, true)) {
            this.rcvDownEnd = new PipeQueue<>(this.config.getPipeRcvDownStackSize());
            this.sndDownEnd = new PipeQueue<>(this.config.getPipeSndUpStackSize());
            this.pipeLayer.init(pipeContext);
        }
    }

    public void releaseLayer(PipeContext pipeContext) {
        if (this.inited.compareAndSet(true, false)) {
            this.pipeLayer.release(pipeContext);
        }
    }

    public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp) throws IOException {
        PipeStatus status = this.pipeLayer.doLayer(context, isRcv, rcvUp, this.rcvDownEnd, sndUp, this.sndDownEnd);

        rcvUp.rcvSubmit();
        this.rcvDownEnd.sndSubmit();
        sndUp.rcvSubmit();
        this.sndDownEnd.sndSubmit();
        return status;
    }

    public PipeStatus doError(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp, PipeExceptionHandler eh) {
        PipeStatus status = this.pipeLayer.doError(context, isRcv, rcvUp, this.rcvDownEnd, sndUp, this.sndDownEnd, eh);

        rcvUp.rcvReset();
        this.rcvDownEnd.sndReset();
        sndUp.rcvReset();
        this.sndDownEnd.sndReset();
        return status;
    }
}