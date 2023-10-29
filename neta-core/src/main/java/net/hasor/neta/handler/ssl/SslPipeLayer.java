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
package net.hasor.neta.handler.ssl;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.handler.*;

import java.io.IOException;
import java.util.Objects;

/**
 * SSL 网络协议层
 */
public class SslPipeLayer implements PipeLayer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> {
    private final SslConfig config;

    public SslPipeLayer(SslConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public void init(PipeContext context) throws Exception {
        SoChannel<?> channel = context.channel();

        long channelID = channel.getChannelID();
        boolean clientMode = channel.isClient();

        SoContext soContext = context.getSoContext();
        if (this.config.getProvider() == SslProvider.JDK) {
            context.context(SslContext.class, new JdkSslContext(channelID, soContext, this.config, clientMode));
        } else {
            throw new UnsupportedOperationException(this.config.getProvider() + " Unsupported.");
        }
    }

    @Override
    public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        if (isRcv) {
            ((SslContextBasic) context.context(SslContext.class)).handRcv(rcvUp, rcvDown, sndUp, sndDown);
        } else {
            ((SslContextBasic) context.context(SslContext.class)).handSnd(rcvUp, rcvDown, sndUp, sndDown);
        }
        return PipeStatus.Next;
    }

    @Override
    public PipeStatus doError(PipeContext context, boolean isRcv, Throwable e, PipeExceptionHandler eh) {
        context.channel().close();
        return PipeStatus.Exit;
    }

    @Override
    public void release(PipeContext context) {

    }
}