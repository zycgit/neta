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
 * Factory class for building type-safe protocol pipeline builders and routing builders.
 * <h3>Pipeline builders</h3>
 * <ul>
 *   <li>{@link #standard()} – chain whose RCV and SND endpoints are both {@code ByteBuf}
 *       (most common for binary protocols).</li>
 *   <li>{@link #object()} – chain with {@code Object} endpoints (useful for testing or
 *       when the outer type is unknown at compile time).</li>
 *   <li>{@link #typed(Class, Class)} – fully typed chain with explicit endpoint types.</li>
 * </ul>
 * <h3>Routing builders</h3>
 * <ul>
 *   <li>{@link #typedRoutingAsStatic} – route is resolved lazily on activation and/or first inbound
 *       data, then kept until switched explicitly.</li>
 *   <li>{@link #typedRoutingAsRealtime} – selector is re-evaluated on each inbound receive pass.</li>
 * </ul>
 * <h3>Example</h3>
 * <pre>
 * ProtoInitializer init = ctx -&gt; {
 *     ProtoHelper.standard()
 *         .nextDuplex("frame",  lineBasedFrameDuplexer)
 *         .nextDuplex("string", stringDuplexer)
 *         .apply(ctx);
 * };
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see ProtoBuilder
 * @see ProtoRoutingBuilder
 */
public final class ProtoHelper {
    /** Create a standalone routing builder using static route selection. */
    public static <RCV_UP, SND_DOWN> ProtoRoutingBuilder<RCV_UP, SND_DOWN> typedRoutingAsStatic(ProtoRoutingSelector<RCV_UP, SND_DOWN> routing) {
        Objects.requireNonNull(routing, "routing is null.");
        return new ProtoRoutingBuilderImpl<>(ProtoConfig.DEFAULT, new ProtoRoutingDuplexer<>(ProtoRoutingMode.STATIC, routing));
    }

    /** Create a standalone routing builder using realtime route selection. */
    public static <RCV_UP, SND_DOWN> ProtoRoutingBuilder<RCV_UP, SND_DOWN> typedRoutingAsRealtime(ProtoRoutingSelector<RCV_UP, SND_DOWN> routing) {
        Objects.requireNonNull(routing, "routing is null.");
        return new ProtoRoutingBuilderImpl<>(ProtoConfig.DEFAULT, new ProtoRoutingDuplexer<>(ProtoRoutingMode.REALTIME, routing));
    }

    /** Create a {@link ProtoBuilder} with {@code ByteBuf} endpoints and default config. */
    public static ProtoBuilder<ByteBuf, ByteBuf> standard() {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    /** Create a {@link ProtoBuilder} with {@code ByteBuf} endpoints and custom config. */
    public static ProtoBuilder<ByteBuf, ByteBuf> standard(ProtoConfig protoConf) {
        return new ProtoHelper().nextTo(protoConf);
    }

    /** Create a {@link ProtoBuilder} with generic {@code Object} endpoints and default config. */
    public static ProtoBuilder<Object, Object> object() {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    /** Create a {@link ProtoBuilder} with generic {@code Object} endpoints and custom config. */
    public static ProtoBuilder<Object, Object> object(ProtoConfig protoConf) {
        return new ProtoHelper().nextTo(protoConf);
    }

    /** Create a typed {@link ProtoBuilder} with specified RCV/SND endpoint types and default config. */
    public static <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> typed(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown) {
        return new ProtoHelper().nextTo(ProtoConfig.DEFAULT);
    }

    /** Create a typed {@link ProtoBuilder} with specified RCV/SND endpoint types and custom config. */
    public static <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> typed(Class<RCV_UP> rcvUp, Class<SND_DOWN> sndDown, ProtoConfig terminalConfig) {
        return new ProtoHelper().nextTo(terminalConfig);
    }

    private <RCV_UP, SND_DOWN> ProtoBuilder<RCV_UP, SND_DOWN> nextTo(ProtoConfig protoConf) {
        return new ProtoBuilderImpl<>(protoConf, new ArrayList<>());
    }

    private static class ProtoBuilderImpl<RCV_DOWN, SND_UP> implements ProtoBuilder<RCV_DOWN, SND_UP> {
        private final ProtoConfig                  defaultConf;
        private final List<Consumer<ProtoContext>> taskAppend;

        ProtoBuilderImpl(ProtoConfig protoConf, List<Consumer<ProtoContext>> taskAppend) {
            this.defaultConf = Objects.requireNonNull(protoConf, "ProtoConfig is null.");
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, //
                ProtoDuplexer<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> duplexer) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(duplexer, "duplexer is null.");

            this.taskAppend.add(c -> c.addLast(name, duplexer));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, //
                ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, ProtoHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(c -> c.addLast(name, decoder, encoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN> ProtoBuilder<NEXT_RCV_DOWN, SND_UP> nextDecoder(String name, ProtoConfig protoConf, //
                ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");

            this.taskAppend.add(c -> c.addLastDecoder(name, decoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <PREV_SND_UP> ProtoBuilder<RCV_DOWN, PREV_SND_UP> nextEncoder(String name, ProtoConfig protoConf, //
                ProtoHandler<PREV_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(c -> c.addLastEncoder(name, encoder));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, PREV_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, PREV_SND_UP> nextRouteAsStatic(String name, ProtoConfig protoConf, //
                ProtoRoutingSelector<NEXT_RCV_DOWN, PREV_SND_UP> routing, Consumer<ProtoRoutingBuilder<NEXT_RCV_DOWN, PREV_SND_UP>> branches) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(name, "name is null.");
            Objects.requireNonNull(routing, "routing is null.");
            Objects.requireNonNull(branches, "branches is null.");

            ProtoRoutingDuplexer<NEXT_RCV_DOWN, PREV_SND_UP> duplexer = new ProtoRoutingDuplexer<>(ProtoRoutingMode.STATIC, routing);
            ProtoRoutingBuilder<NEXT_RCV_DOWN, PREV_SND_UP> routeBuilder = new ProtoRoutingBuilderImpl<>(this.defaultConf, duplexer);
            branches.accept(routeBuilder);
            this.taskAppend.add(c -> c.addLast(name, duplexer));
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, PREV_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, PREV_SND_UP> nextRouteAsRealtime(String name, ProtoConfig protoConf, //
                ProtoRoutingSelector<NEXT_RCV_DOWN, PREV_SND_UP> routing, Consumer<ProtoRoutingBuilder<NEXT_RCV_DOWN, PREV_SND_UP>> branches) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(name, "name is null.");
            Objects.requireNonNull(routing, "routing is null.");
            Objects.requireNonNull(branches, "branches is null.");

            ProtoRoutingDuplexer<NEXT_RCV_DOWN, PREV_SND_UP> duplexer = new ProtoRoutingDuplexer<>(ProtoRoutingMode.REALTIME, routing);
            ProtoRoutingBuilder<NEXT_RCV_DOWN, PREV_SND_UP> routeBuilder = new ProtoRoutingBuilderImpl<>(this.defaultConf, duplexer);
            branches.accept(routeBuilder);
            this.taskAppend.add(c -> c.addLast(name, duplexer));
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

    private static class ProtoRoutingBuilderImpl<RCV_DOWN, SND_UP> implements ProtoRoutingBuilder<RCV_DOWN, SND_UP> {
        private final ProtoConfig                            defaultConf;
        private final ProtoRoutingDuplexer<RCV_DOWN, SND_UP> duplexer;

        public ProtoRoutingBuilderImpl(ProtoConfig defaultConf, ProtoRoutingDuplexer<RCV_DOWN, SND_UP> duplexer) {
            this.defaultConf = defaultConf;
            this.duplexer = duplexer;
        }

        @Override
        public ProtoRoutingBuilder<RCV_DOWN, SND_UP> branchByInitializer(String name, ProtoInitializer initializer) {
            duplexer.addBranch(name, initializer);
            return this;
        }

        @Override
        public ProtoRoutingBuilder<RCV_DOWN, SND_UP> branch(String name, Consumer<ProtoBuilder<RCV_DOWN, SND_UP>> branchBuilder) {
            Objects.requireNonNull(branchBuilder, "branchBuilder is null.");

            ProtoBuilder<RCV_DOWN, SND_UP> builder = new ProtoBuilderImpl<>(this.defaultConf, new ArrayList<>());
            branchBuilder.accept(builder);
            this.duplexer.addBranch(name, builder.build());
            return this;
        }

        @Override
        public ProtoDuplexer<RCV_DOWN, ?, ?, SND_UP> build() {
            return this.duplexer;
        }
    }
}