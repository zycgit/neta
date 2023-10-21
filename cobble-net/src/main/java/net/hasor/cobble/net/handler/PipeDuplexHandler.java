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

import java.io.IOException;

/**
 * Used to represent a unidirectional data processor, two {@link PipeDuplexHandler}`s in opposite directions can to {@link PipeLayer}
 *
 * @version : 2023-10-17
 * @author 赵永春 (zyc@hasor.net)
 * @see PipeLayer
 */
public class PipeDuplexHandler<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> implements PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final PipeHandler<RCV_UP, RCV_DOWN> decoder;
    private final PipeHandler<SND_UP, SND_DOWN> encoder;

    public PipeDuplexHandler(PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
        this.decoder = decoder;
        this.encoder = encoder;
    }

    @Override
    public void initLayer(PipeContext pipeContext) throws Exception {
        this.decoder.initHandler(pipeContext);
        this.encoder.initHandler(pipeContext);
    }

    @Override
    public PipeStatus doLayer(PipeContext context, boolean isRcv, RCV_UP rcvUp, RCV_DOWN rcvDown, SND_UP sndUp, SND_DOWN sndDown) throws IOException {
        if (isRcv) {
            return this.decoder.doHandler(context, rcvUp, rcvDown);
        } else {
            return this.encoder.doHandler(context, sndUp, sndDown);
        }
    }

    @Override
    public void releaseLayer(PipeContext pipeContext) {
        this.decoder.releaseHandler(pipeContext);
        this.encoder.releaseHandler(pipeContext);
    }
}