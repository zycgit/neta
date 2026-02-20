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
package net.hasor.neta.channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Application stack Builder
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public final class ProtoHelper {
    public static ProtoBuilder<ByteBuf, ByteBuf> standard() {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    public static ProtoBuilder<ByteBuf, ByteBuf> standard(ProtoConfig protoConf) {
        return new ProtoHelper().nextTo(protoConf);
    }

    public static ProtoBuilder<Object, Object> object() {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    public static ProtoBuilder<Object, Object> object(ProtoConfig protoConf) {
        return new ProtoHelper().nextTo(protoConf);
    }

    public static <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> typed(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown) {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    public static <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> typed(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown, ProtoConfig terminalConfig) {
        return new ProtoHelper().nextTo(terminalConfig);
    }

    private <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> nextTo(ProtoConfig protoConf) {
        return new ProtoBuilderImpl<>(protoConf, new ArrayList<>());
    }

    private static final class ProtoBuilderImpl<RCV_DOWN, SND_UP> implements ProtoBuilder<RCV_DOWN, SND_UP> {
        private final ProtoConfig                  defaultConf;
        private final List<Consumer<ProtoContext>> taskAppend;

        ProtoBuilderImpl(ProtoConfig protoConf, List<Consumer<ProtoContext>> taskAppend) {
            this.defaultConf = Objects.requireNonNull(protoConf, "ProtoConfig is null.");
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> duplexer) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(duplexer, "duplexer is null.");

            this.taskAppend.add(c -> c.addLast(name, duplexer));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, ProtoHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(c -> c.addLast(name, decoder, encoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN> ProtoBuilder<NEXT_RCV_DOWN, SND_UP> nextDecoder(String name, ProtoConfig protoConf, ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");

            this.taskAppend.add(c -> c.addLastDecoder(name, decoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <PREV_SND_UP> ProtoBuilder<RCV_DOWN, PREV_SND_UP> nextEncoder(String name, ProtoConfig protoConf, ProtoHandler<PREV_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(c -> c.addLastEncoder(name, encoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public ProtoBuilder<Object, Object> nextRoute(String name, ProtoConfig protoConf, ProtoRouting<RCV_DOWN> routing, Consumer<ProtoRoutingDuplexer.Builder<RCV_DOWN>> branches) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(routing, "routing is null.");
            Objects.requireNonNull(branches, "branches is null.");

            this.taskAppend.add(c -> {
                ProtoRoutingDuplexer.Builder<RCV_DOWN> builder = ProtoRoutingDuplexer.newBuilder(routing);
                branches.accept(builder);
                ProtoRoutingDuplexer<RCV_DOWN> routingDuplexer = builder.build(c);
                c.addLast(name, routingDuplexer);
            });
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public ProtoInitializer build() {
            return ctx -> {
                for (Consumer<ProtoContext> consumer : taskAppend) {
                    consumer.accept(ctx);
                }
            };
        }
    }
}