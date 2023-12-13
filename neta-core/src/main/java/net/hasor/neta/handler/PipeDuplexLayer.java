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

import java.io.IOException;

/**
 * Used to represent a unidirectional data processor, two {@link PipeDuplexLayer}`s in opposite directions can to {@link PipeLayer}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see PipeLayer
 */
public class PipeDuplexLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> implements PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final PipeHandler<RCV_UP, RCV_DOWN> decoder;
    private final PipeHandler<SND_UP, SND_DOWN> encoder;

    public PipeDuplexLayer(PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
        this.decoder = decoder;
        this.encoder = encoder;
    }

    @Override
    public void init(PipeContext context) throws Exception {
        this.decoder.initHandler(context);
        this.encoder.initHandler(context);
    }

    @Override
    public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeSndQueue<RCV_DOWN> rcvDown, PipeRcvQueue<SND_UP> sndUp, PipeSndQueue<SND_DOWN> sndDown) throws IOException {
        if (isRcv) {
            return this.decoder.doHandler(context, rcvUp, rcvDown);
        } else {
            return this.encoder.doHandler(context, sndUp, sndDown);
        }
    }

    @Override
    public PipeStatus doError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHandler eh) {
        if (isRcv) {
            return this.decoder.doError(context, e, eh);
        } else {
            return this.encoder.doError(context, e, eh);
        }
    }

    @Override
    public void release(PipeContext context) {
        this.decoder.releaseHandler(context);
        this.encoder.releaseHandler(context);
    }
}