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
import net.hasor.neta.channel.PipeStackFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Application stack PipeBuilder
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
public final class PipeInitializer implements PipeBuilder {

    @Override
    public <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeLayer<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> pipeLayer) {
        PipeStackBuilder<ByteBuf, ByteBuf> builder = new PipeStackBuilderImpl<>(new ArrayList<>());
        return builder.nextTo(name, pipeConfig, pipeLayer);
    }

    @Override
    public <RCV_DOWN, SND_UP> PipeStackBuilder<RCV_DOWN, SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        PipeStackBuilder<ByteBuf, ByteBuf> builder = new PipeStackBuilderImpl<>(new ArrayList<>());
        return builder.nextTo(name, pipeConfig, new PipeDuplexHandler<>(decoder, encoder));
    }

    static class PipeStackBuilderImpl<RCV_DOWN, SND_UP> implements PipeStackBuilder<RCV_DOWN, SND_UP> {
        private final List<Consumer<PipeChainRoot>> taskAppend;

        PipeStackBuilderImpl(List<Consumer<PipeChainRoot>> taskAppend) {
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeLayer<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> pipeLayer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeLayerInvocation<>(name, pipeConfig, pipeLayer));
            });
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextTo(String name, PipeConfig pipeConfig, PipeHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, PipeHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> pipeLayer = new PipeDuplexHandler<>(decoder, encoder);
            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeLayerInvocation<>(name, pipeConfig, pipeLayer));
            });
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipeStackBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> bindReceive(List<PipeReceiveListener<NEXT_RCV_DOWN>> listeners) {
            this.taskAppend.add(chainRoot -> {
                chainRoot.addListener(listeners);
            });
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public PipeStackFactory buildFactory() {
            return pipeCtx -> {
                PipeChainRoot root = new PipeChainRoot();
                for (Consumer<PipeChainRoot> consumer : this.taskAppend) {
                    consumer.accept(root);
                }
                root.initLayer(pipeCtx);
                return root;
            };
        }
    }
}