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
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Internal wrapper that adapts a single encoder into a duplexer.
 * <p>When the upper-level API only appends an outbound encoder, this wrapper turns the
 * outbound-only handler into a duplexer that can participate in the unified pipeline.</p>
 * <p>The outbound side is handled by the encoder, while the inbound side remains a transparent
 * pass-through. This makes it suitable for protocol steps that only rewrite the send chain and
 * leave the receive chain unchanged.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-10-02
 */
class ProtoEncoderDuplexWrap<RCV, SND_UP, SND_DOWN> implements ProtoDuplex<RCV, RCV, SND_UP, SND_DOWN> {
    private final ProtoHandler<SND_UP, SND_DOWN> encoder;

    ProtoEncoderDuplexWrap(ProtoHandler<SND_UP, SND_DOWN> encoder) {
        this.encoder = encoder;
    }

    /** {@inheritDoc} */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.encoder.onInit(name, sndSize, context);
    }

    /** {@inheritDoc} */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.encoder.onActive(context);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        return isRcv || this.encoder.onEvent(context, event);
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV> rcvUp, ProtoSndQueue<RCV> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
        if (isRcv) {
            rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
            return ProtoStatus.Next;
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return ProtoStatus.Next;
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onClose(ProtoContext context) {
        this.encoder.onClose(context);
    }
}