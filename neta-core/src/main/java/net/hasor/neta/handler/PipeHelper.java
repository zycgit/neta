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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.Pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Application stack PipeBuilder
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public final class PipeHelper {
    public static PipeBuilder<ByteBuf, ByteBuf> builder() {
        return new PipeHelper().nextTo(PipeConfig.DEFAULT);
    }

    public static PipeBuilder<ByteBuf, ByteBuf> builder(PipeConfig pipeConfig) {
        return new PipeHelper().nextTo(pipeConfig);
    }

    public static <RCV_UP, SND_DOWN> PipeBuilder<RCV_UP, SND_DOWN> embedded(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown) {
        return new PipeHelper().nextTo(PipeConfig.DEFAULT);
    }

    public static <RCV_UP, SND_DOWN> PipeBuilder<RCV_UP, SND_DOWN> embedded(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown, PipeConfig terminalConfig) {
        return new PipeHelper().nextTo(terminalConfig);
    }

    private <RCV_UP, SND_DOWN> PipeBuilder<RCV_UP, SND_DOWN> nextTo(PipeConfig pipeConfig) {
        return new PipeStackBuilderImpl<>(pipeConfig, new ArrayList<>());
    }

    class PipeStackBuilderImpl<RCV_DOWN, SND_UP> implements PipeBuilder<RCV_DOWN, SND_UP> {
        private final PipeConfig                    defaultConf;
        private final List<Consumer<PipeChainRoot>> taskAppend;

        PipeStackBuilderImpl(PipeConfig pipeConfig, List<Consumer<PipeChainRoot>> taskAppend) {
            this.defaultConf = Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeDuplex<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> duplexer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(duplexer, "pipeLayer is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, duplexer));
            });
            return new PipeStackBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, PipeHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> pipeLayer = new PipeDuplexHandler<>(decoder, encoder);
            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, pipeLayer));
            });
            return new PipeStackBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN> PipeBuilder<NEXT_RCV_DOWN, SND_UP> nextDecoder(String name, PipeConfig pipeConfig, PipeHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, new DecoderPipeDuplexWrap<>(decoder)));
            });
            return new PipeStackBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <SND_UP1> PipeBuilder<RCV_DOWN, SND_UP1> nextEncoder(String name, PipeConfig pipeConfig, PipeHandler<SND_UP1, SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, new EncoderPipeDuplexWrap<>(encoder)));
            });
            return new PipeStackBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <T> Pipeline<T> build() {
            PipeChainRoot root = new PipeChainRoot(this.defaultConf);
            for (Consumer<PipeChainRoot> consumer : taskAppend) {
                consumer.accept(root);
            }
            return (Pipeline<T>) root;
        }
    }

    static class DecoderPipeDuplexWrap<RCV_UP, RCV_DOWN, SND> implements PipeDuplex<RCV_UP, RCV_DOWN, SND, SND> {
        private final PipeHandler<RCV_UP, RCV_DOWN> decoder;

        public DecoderPipeDuplexWrap(PipeHandler<RCV_UP, RCV_DOWN> decoder) {
            this.decoder = decoder;
        }

        @Override
        public void onInit(PipeContext context) throws Throwable {
            this.decoder.onInit(context);
        }

        @Override
        public void onActive(PipeContext context) throws Throwable {
            this.decoder.onActive(context);
        }

        @Override
        public PipeStatus onMessage(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeSndQueue<RCV_DOWN> rcvDown, PipeRcvQueue<SND> sndUp, PipeSndQueue<SND> sndDown) throws Throwable {
            if (isRcv) {
                return this.decoder.onMessage(context, rcvUp, rcvDown);
            } else {
                sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
                return sndUp.hasMore() && !sndDown.hasSlot() ? PipeStatus.Back : PipeStatus.Next;
            }
        }

        @Override
        public PipeStatus onError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHolder eh) throws Throwable {
            if (isRcv) {
                return this.decoder.onError(context, e, eh);
            } else {
                return PipeStatus.Next;
            }
        }

        @Override
        public void onClose(PipeContext context) {
            this.decoder.onClose(context);
        }
    }

    static class EncoderPipeDuplexWrap<RCV, SND_UP, SND_DOWN> implements PipeDuplex<RCV, RCV, SND_UP, SND_DOWN> {
        private final PipeHandler<SND_UP, SND_DOWN> encoder;

        public EncoderPipeDuplexWrap(PipeHandler<SND_UP, SND_DOWN> encoder) {
            this.encoder = encoder;
        }

        @Override
        public void onInit(PipeContext context) throws Throwable {
            this.encoder.onInit(context);
        }

        @Override
        public void onActive(PipeContext context) throws Throwable {
            this.encoder.onActive(context);
        }

        @Override
        public PipeStatus onMessage(PipeContext context, boolean isRcv, PipeRcvQueue<RCV> rcvUp, PipeSndQueue<RCV> rcvDown, PipeRcvQueue<SND_UP> sndUp, PipeSndQueue<SND_DOWN> sndDown) throws Throwable {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
                return rcvUp.hasMore() && !rcvDown.hasSlot() ? PipeStatus.Back : PipeStatus.Next;
            } else {
                return this.encoder.onMessage(context, sndUp, sndDown);
            }
        }

        @Override
        public PipeStatus onError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHolder eh) throws Throwable {
            if (isRcv) {
                return PipeStatus.Next;
            } else {
                return this.encoder.onError(context, e, eh);
            }
        }

        @Override
        public void onClose(PipeContext context) {
            this.encoder.onClose(context);
        }
    }
}