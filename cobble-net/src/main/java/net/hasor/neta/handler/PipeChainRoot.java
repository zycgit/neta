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
import net.hasor.cobble.ArrayUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeStack;
import net.hasor.neta.channel.SoResManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * root Application stack
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeChainRoot extends PipeStack {
    //    private NetChannel                      channel;
    private static final ByteBuf                               EMPTY = ByteBufAllocator.DEFAULT.wrap(ArrayUtils.EMPTY_BYTE_ARRAY);
    private final        List<PipeLayerInvocation<?, ?, ?, ?>> layers;
    private              List<PipeReceiveListener<?>>          listeners;

    public PipeChainRoot() {
        this.layers = new ArrayList<>();
    }

    public void addLayer(PipeLayerInvocation<?, ?, ?, ?> pipeLayer) {
        this.layers.add(pipeLayer);
    }

    public <NEXT_RCV_DOWN> void addListener(List<PipeReceiveListener<NEXT_RCV_DOWN>> listeners) {
        this.listeners = new ArrayList<>(listeners);
    }

    public void initLayer(SoResManager rm) {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.initLayer(rm);
        }
    }

    @Override
    protected ByteBuf rcvLayer(PipeContext pipeContext, ByteBuf rcvData) {
        //pipeContext.clearFlash();
        ByteBuf rootSndDown = pipeContext.getSoResManager().newByteBuf(-1);
        PipeStatus status = null;

        do {
            for (int i = 0; i < this.layers.size(); i++) {
                try {
                    status = this.doRcvLayer(pipeContext, rcvData, i);
                    switch (status) {
                        case Success:
                        case Again:
                            continue;
                        case Finish:
                        case StartOver:
                            break;
                    }
                } catch (Exception e) {
                    // TODO xxx
                }
            }
        } while (status == PipeStatus.StartOver);

        // last endpoint
        if (!this.layers.isEmpty()) {
            Object rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();
            for (PipeReceiveListener listener : this.listeners) {
                listener.onReceive(pipeContext, rcvDown);
            }
        }

        return rootSndDown;
    }

    //                 PipeLayer(0)                    PipeLayer (1)
    //          /------------------------\      /------------------------\
    //          |                        |      |                        |
    //          |             /----------+------+----------\             |
    //  DATA -> | RCV_UP      | RCV_DOWN    ->    RCV_UP   |    RCV_DOWN |  -> ...
    //          |             |                            |             |
    //  ...  <- | SND_DOWN    | SND_UP      <-    SND_DOWN |      SND_UP |  <- DATA
    //          |             \----------+------+----------/             |
    //          |                        |      |                        |
    //          \------------------------/      \------------------------/
    private PipeStatus doRcvLayer(PipeContext pipeContext, ByteBuf rcvUp, int i) throws IOException {
        Object useRcvUp = i == 0 ? rcvUp : this.layers.get(i - 1).getRcvDown();
        Object useSndUp = i < (this.layers.size() - 1) ? this.layers.get(i + 1).getSndDown() : EMPTY;

        PipeStatus status;
        do {
            PipeLayerInvocation layer = this.layers.get(i);
            status = layer.doLayer(pipeContext, true, useRcvUp, useSndUp);
            if (status == null) {
                throw new IllegalStateException("Missing return status");
            }
        } while (status == PipeStatus.Again);

        return status;
    }

    @Override
    protected ByteBuf sndLayer(PipeContext pipeContext, Object writeData) {
        //pipeContext.clearFlash();
        ByteBuf rootSndDown = pipeContext.getSoResManager().newByteBuf(-1);
        PipeStatus status = null;

        do {
            for (int i = this.layers.size() - 1; i >= 0; i--) {
                try {
                    status = PipeStatus.Success;
                    //                    status = this.doSndLayer(pipeContext, rootSndDown, i);
                    switch (status) {
                        case Success:
                        case Again:
                            continue;
                        case Finish:
                        case StartOver:
                            break;
                    }
                } catch (Exception e) {
                    // TODO xxx
                }
            }
        } while (status == PipeStatus.StartOver);

        return rootSndDown;
    }

    private PipeStatus doSndLayer(PipeContext pipeContext, ByteBuf rootSndDown, int i) throws IOException {
        //                 PipeLayer(0)                    PipeLayer (1)
        //          /------------------------\      /------------------------\
        //          |                        |      |                        |
        //          |             /----------+------+----------\             |
        //  DATA -> | RCV_UP      | RCV_DOWN    ->    RCV_UP   |    RCV_DOWN |  -> ...
        //          |             |                            |             |
        //  ...  <- | SND_DOWN    | SND_UP      <-    SND_DOWN |      SND_UP |  <- DATA
        //          |             \----------+------+----------/             |
        //          |                        |      |                        |
        //          \------------------------/      \------------------------/
        //
        Object useRcvUp = i == 0 ? EMPTY : this.layers.get(i).getRcvDown();

        // SND_UP and SND_DOWN are themselves the same thing,
        // It distinguished as UP and DOWN Only in two PipeLayer
        // So need to keep using the target side.
        //
        //                PipeLayer (0)               PipeLayer (1)
        //           /--------------------\      /--------------------\
        // rcvBuf <- | xxx         SND_UP |  <-  | xxx         SND_UP |  <- APP
        //           \--------------------/      \--------------------/
        Object useSndDown = i == 0 ? rootSndDown : this.layers.get(i).getSndDown();

        PipeStatus status;
        do {
            PipeLayerInvocation layer = this.layers.get(i);
            status = layer.doLayer(pipeContext, false, useRcvUp, useSndDown);
            if (status == null) {
                throw new IllegalStateException("Missing return status");
            }
        } while (status == PipeStatus.Again);

        return status;
    }
}