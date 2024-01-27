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

/**
 * Used to represent a unidirectional data processor, two {@link PipeDuplexHandler}`s in opposite directions can to {@link PipeDuplex}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-17
 * @see PipeDuplex
 */
public class PipeDuplexHandler<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> implements PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final PipeHandler<RCV_UP, RCV_DOWN> decoder;
    private final PipeHandler<SND_UP, SND_DOWN> encoder;

    public PipeDuplexHandler(PipeHandler<RCV_UP, RCV_DOWN> decoder, PipeHandler<SND_UP, SND_DOWN> encoder) {
        this.decoder = decoder;
        this.encoder = encoder;
    }

    @Override
    public void onInit(PipeContext context) throws Throwable {
        this.decoder.onInit(context);
        this.encoder.onInit(context);
    }

    @Override
    public void onActive(PipeContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    public PipeStatus onMessage(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeSndQueue<RCV_DOWN> rcvDown, PipeRcvQueue<SND_UP> sndUp, PipeSndQueue<SND_DOWN> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public PipeStatus onError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(PipeContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}