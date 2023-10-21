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
import net.hasor.cobble.net.bytebuf.ByteBuf;
import net.hasor.cobble.net.channel.PipeStackFactory;

import java.util.Objects;

/**
 * Application stack PipeBuilder
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
public final class PipeInitializer implements PipeBuilder {
    @Override
    public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeBytesToBytesLayer pipeLayer) {
        Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        PipeChainRoot chainRoot = new PipeChainRoot();
        chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, pipeLayer));
        return new PipeStackBuilderImpl<>(chainRoot);
    }

    @Override
    public <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeConfig pipeConfig, PipeBytesToMessageLayer<RCV_DOWN, SND_UP> pipeLayer) {
        Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        PipeChainRoot chainRoot = new PipeChainRoot();
        chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, pipeLayer));
        return new PipeStackBuilderImpl<>(chainRoot);
    }

    @Override
    public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeBytesToBytesHandler decoder, PipeBytesToBytesHandler encoder) {
        Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        PipeChainRoot chainRoot = new PipeChainRoot();
        PipeDuplexHandler<ByteBuf, ByteBuf, ByteBuf, ByteBuf> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
        chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, duplexHandler));
        return new PipeStackBuilderImpl<>(chainRoot);
    }

    @Override
    public <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(PipeConfig pipeConfig, PipeBytesToMessageHandler<RCV_DOWN> decoder, PipeMessageToBytesHandler<SND_UP> encoder) {
        Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
        Objects.requireNonNull(decoder, "decoder is null.");
        Objects.requireNonNull(encoder, "encoder is null.");

        PipeChainRoot chainRoot = new PipeChainRoot();
        PipeDuplexHandler<ByteBuf, PipeSndQueue<RCV_DOWN>, PipeRcvQueue<SND_UP>, ByteBuf> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
        chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, duplexHandler));
        return new PipeStackBuilderImpl<>(chainRoot);
    }

    static class PipeStackBuilderImpl<RCV_DOWN, SND_UP> implements PipeStackBuilder<RCV_DOWN, SND_UP> {
        private final PipeChainRoot chainRoot;

        PipeStackBuilderImpl(PipeChainRoot chainRoot) {
            this.chainRoot = chainRoot;
        }

        @Override
        public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeBytesToBytesLayer pipeLayer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, pipeLayer));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(PipeConfig pipeConfig, PipeBytesToMessageLayer<NEXT_RCV_DOWN, NEXT_SND_UP> pipeLayer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, pipeLayer));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(PipeConfig pipeConfig, PipeMessageToMessageLayer<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> pipeLayer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, pipeLayer));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeMessageToBytesLayer<RCV_DOWN, SND_UP> pipeLayer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, pipeLayer));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeBytesToBytesHandler decoder, PipeBytesToBytesHandler encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<ByteBuf, ByteBuf, ByteBuf, ByteBuf> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, duplexHandler));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(PipeConfig pipeConfig, PipeBytesToMessageHandler<NEXT_RCV_DOWN> decoder, PipeMessageToBytesHandler<NEXT_SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<ByteBuf, PipeSndQueue<NEXT_RCV_DOWN>, PipeRcvQueue<NEXT_SND_UP>, ByteBuf> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, duplexHandler));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(PipeConfig pipeConfig, PipeMessageToMessageHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, PipeMessageToMessageHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<PipeRcvQueue<RCV_DOWN>, PipeSndQueue<NEXT_RCV_DOWN>, PipeRcvQueue<NEXT_SND_UP>, PipeSndQueue<SND_UP>> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, false, false, duplexHandler));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public PipeStackBuilder<ByteBuf, ByteBuf> nextTo(PipeConfig pipeConfig, PipeMessageToBytesHandler<RCV_DOWN> decoder, PipeBytesToMessageHandler<SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<PipeRcvQueue<RCV_DOWN>, ByteBuf, ByteBuf, PipeSndQueue<SND_UP>> duplexHandler = new PipeDuplexHandler<>(decoder, encoder);
            this.chainRoot.addLayer(new PipeLayerInvocation<>(pipeConfig, true, true, duplexHandler));
            return new PipeStackBuilderImpl<>(this.chainRoot);
        }

        @Override
        public PipeStackFactory buildFactory() {
            return pipeCtx -> {
                //                    this.chainRoot.init();
                //                    this.chainRoot;
                return null;
            };
        }
    }
}