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
        return new PipeHelper().nextTo(new PipeConfig());
    }

    public static PipeBuilder<ByteBuf, ByteBuf> builder(PipeConfig pipeConfig) {
        return new PipeHelper().nextTo(pipeConfig);
    }

    public static <RCV_UP, SND_DOWN> PipeBuilder<RCV_UP, SND_DOWN> embedded() {
        return new PipeHelper().nextTo(new PipeConfig());
    }

    public static <RCV_UP, SND_DOWN> PipeBuilder<RCV_UP, SND_DOWN> embedded(PipeConfig pipeConfig) {
        return new PipeHelper().nextTo(pipeConfig);
    }

    //
    //

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
        public PipeConfig pipeConfig() {
            return this.defaultConf;
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
        public <T> Pipeline<T> build() {
            PipeChainRoot root = new PipeChainRoot(pipeConfig());
            for (Consumer<PipeChainRoot> consumer : taskAppend) {
                consumer.accept(root);
            }
            return (Pipeline<T>) root;
        }
    }
}