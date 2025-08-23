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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
        private final ProtoConfig                    defaultConf;
        private final List<Consumer<ProtoChainRoot>> taskAppend;

        ProtoBuilderImpl(ProtoConfig protoConf, List<Consumer<ProtoChainRoot>> taskAppend) {
            this.defaultConf = Objects.requireNonNull(protoConf, "ProtoConfig is null.");
            this.taskAppend = taskAppend;
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> duplexer) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(duplexer, "duplexer is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addProtoStack(new ProtoInvocation<>(name, protoConf, duplexer));
            });
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN, NEXT_SND_UP> ProtoBuilder<NEXT_RCV_DOWN, NEXT_SND_UP> nextDuplex(String name, ProtoConfig protoConf, ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder, ProtoHandler<NEXT_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            ProtoDuplexerHandler<RCV_DOWN, NEXT_RCV_DOWN, NEXT_SND_UP, SND_UP> handler = new ProtoDuplexerHandler<>(decoder, encoder);
            this.taskAppend.add(chainRoot -> {
                chainRoot.addProtoStack(new ProtoInvocation<>(name, protoConf, handler));
            });
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <NEXT_RCV_DOWN> ProtoBuilder<NEXT_RCV_DOWN, SND_UP> nextDecoder(String name, ProtoConfig protoConf, ProtoHandler<RCV_DOWN, NEXT_RCV_DOWN> decoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(decoder, "decoder is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addProtoStack(new ProtoInvocation<>(name, protoConf, new DecoderDuplexWrap<>(decoder)));
            });
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <PREV_SND_UP> ProtoBuilder<RCV_DOWN, PREV_SND_UP> nextEncoder(String name, ProtoConfig protoConf, ProtoHandler<PREV_SND_UP, SND_UP> encoder) {
            Objects.requireNonNull(protoConf, "protoConf is null.");
            Objects.requireNonNull(encoder, "encoder is null.");

            this.taskAppend.add(chainRoot -> {
                chainRoot.addProtoStack(new ProtoInvocation<>(name, protoConf, new EncoderDuplexWrap<>(encoder)));
            });
            return new ProtoBuilderImpl<>(this.defaultConf, this.taskAppend);
        }

        @Override
        public <T> ProtoStack<T> build(EventBus eventBus) {
            ProtoChainRoot root = new ProtoChainRoot(this.defaultConf, eventBus);
            for (Consumer<ProtoChainRoot> consumer : taskAppend) {
                consumer.accept(root);
            }
            return (ProtoStack<T>) root;
        }
    }

    private static class DecoderDuplexWrap<RCV_UP, RCV_DOWN, SND> implements ProtoDuplexer<RCV_UP, RCV_DOWN, SND, SND> {
        private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;

        public DecoderDuplexWrap(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
            this.decoder = decoder;
        }

        @Override
        public void onInit(ProtoContext context) throws Throwable {
            this.decoder.onInit(context);
        }

        @Override
        public void onActive(ProtoContext context) throws Throwable {
            this.decoder.onActive(context);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND> sndUp, ProtoSndQueue<SND> sndDown) throws Throwable {
            if (isRcv) {
                return this.decoder.onMessage(context, rcvUp, rcvDown);
            } else {
                sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
                return sndUp.hasMore() && !sndDown.hasSlot() ? ProtoStatus.Back : ProtoStatus.Next;
            }
        }

        @Override
        public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
            if (isRcv) {
                return this.decoder.onError(context, e, eh);
            } else {
                return ProtoStatus.Next;
            }
        }

        @Override
        public void onClose(ProtoContext context) {
            this.decoder.onClose(context);
        }
    }

    private static class EncoderDuplexWrap<RCV, SND_UP, SND_DOWN> implements ProtoDuplexer<RCV, RCV, SND_UP, SND_DOWN> {
        private final ProtoHandler<SND_UP, SND_DOWN> encoder;

        public EncoderDuplexWrap(ProtoHandler<SND_UP, SND_DOWN> encoder) {
            this.encoder = encoder;
        }

        @Override
        public void onInit(ProtoContext context) throws Throwable {
            this.encoder.onInit(context);
        }

        @Override
        public void onActive(ProtoContext context) throws Throwable {
            this.encoder.onActive(context);
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV> rcvUp, ProtoSndQueue<RCV> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
            if (isRcv) {
                rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
                return rcvUp.hasMore() && !rcvDown.hasSlot() ? ProtoStatus.Back : ProtoStatus.Next;
            } else {
                return this.encoder.onMessage(context, sndUp, sndDown);
            }
        }

        @Override
        public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
            if (isRcv) {
                return ProtoStatus.Next;
            } else {
                return this.encoder.onError(context, e, eh);
            }
        }

        @Override
        public void onClose(ProtoContext context) {
            this.encoder.onClose(context);
        }
    }
}