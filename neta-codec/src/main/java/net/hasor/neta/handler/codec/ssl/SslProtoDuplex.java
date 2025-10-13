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
package net.hasor.neta.handler.codec.ssl;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.util.Objects;

/**
 * SSL 网络协议层
 */
public class SslProtoDuplex implements ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    private final SslConfig config;

    public SslProtoDuplex(SslConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        SoChannel<?> channel = context.getChannel();
        String stackName = context.getStackName();

        if (this.config.getProvider() == SslProvider.JSSE) {
            context.context(SslContext.class, new JdkSslContext(channel, stackName, context, this.config, channel.isClient()));
        } else {
            throw new UnsupportedOperationException(this.config.getProvider() + " Unsupported.");
        }
    }

    @Override
    public void onActive(ProtoContext context) throws Exception {
        if (context.getChannel().isClient()) {
            context.sendData(ByteBuf.EMPTY);// make sure to trigger the handshake
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        if (isRcv) {
            return ((SslContextBasic) context.context(SslContext.class)).handRcv(rcvUp, rcvDown, sndUp, sndDown);
        } else {
            return ((SslContextBasic) context.context(SslContext.class)).handSnd(rcvUp, rcvDown, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {

    }
}