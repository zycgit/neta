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
import net.hasor.neta.channel.PipeInitializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Application stack PipeBuilder
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public final class PipeHelper implements PipeBuilder {
    private final AtomicReference<PipeConfig> defaultConfigRef = new AtomicReference<>(new PipeConfig());

    public static PipeInitializer empty() {
        return new PipeHelper().nextTo().build();
    }

    public static PipelineBuilder<ByteBuf, ByteBuf> builder() {
        return new PipeHelper().nextTo();
    }

    public static <RCV_UP, SND_DOWN> PipelineBuilder<RCV_UP, SND_DOWN> embedded() {
        return new PipeHelper().nextTo();
    }

    @Override
    public <RCV_UP, SND_DOWN> PipelineBuilder<RCV_UP, SND_DOWN> pipeConfig(PipeConfig pipeConfig) {
        defaultConfigRef.set(Objects.requireNonNull(pipeConfig, "pipeConfig is null."));
        return new PipeStackBuilderImpl<>(new ArrayList<>());
    }

    @Override
    public PipeConfig pipeConfig() {
        return defaultConfigRef.get();
    }

    @Override
    public <RCV_UP, SND_DOWN> PipelineBuilder<RCV_UP, SND_DOWN> nextTo() {
        return new PipeStackBuilderImpl<>(new ArrayList<>());
    }

    @Override
    public <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeDuplex<ByteBuf, RCV_DOWN, SND_UP, ByteBuf> duplexer) {
        PipelineBuilder<ByteBuf, ByteBuf> builder = new PipeStackBuilderImpl<>(new ArrayList<>());
        return builder.nextDuplex(name, pipeConfig, duplexer);
    }

    @Override
    public <RCV_DOWN, SND_UP> PipelineBuilder<RCV_DOWN, SND_UP> nextHandler(String name, PipeConfig pipeConfig, PipeHandler<ByteBuf, RCV_DOWN> decoder, PipeHandler<SND_UP, ByteBuf> encoder) {
        PipelineBuilder<ByteBuf, ByteBuf> builder = new PipeStackBuilderImpl<>(new ArrayList<>());
        return builder.nextDuplex(name, pipeConfig, new PipeDuplexHandler<>(decoder, encoder));
    }

    class PipeStackBuilderImpl<RCV_DOWN, SND_UP> implements PipelineBuilder<RCV_DOWN, SND_UP> {
        private final List<Consumer<PipeChainRoot>> taskAppend;

        PipeStackBuilderImpl(List<Consumer<PipeChainRoot>> taskAppend) {
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipelineBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> pipeConfig(PipeConfig pipeConfig) {
            defaultConfigRef.set(Objects.requireNonNull(pipeConfig, "pipeConfig is null."));
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public PipeConfig pipeConfig() {
            return defaultConfigRef.get();
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipelineBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, PipeConfig pipeConfig, PipeDuplex<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> duplexer) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(duplexer, "pipeLayer is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, duplexer));
            });
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> PipelineBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextHandler(String name, PipeConfig pipeConfig, PipeHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, PipeHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(pipeConfig, "pipeConfig is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            PipeDuplexHandler<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> pipeLayer = new PipeDuplexHandler<>(decoder, encoder);
            this.taskAppend.add(chainRoot -> {
                chainRoot.addLayer(new PipeInvocation<>(name, pipeConfig, pipeLayer));
            });
            return new PipeStackBuilderImpl<>(this.taskAppend);
        }

        @Override
        public PipeInitializer build(PipeConfig rootConfig) {
            return (context) -> {
                PipeChainRoot root = new PipeChainRoot(rootConfig);
                for (Consumer<PipeChainRoot> consumer : taskAppend) {
                    consumer.accept(root);
                }
                return root;
            };
        }
    }
}