/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Internal wrapper that adapts a single decoder into a duplexer.
 * <p>When the upper-level API only appends an inbound decoder, this wrapper turns the inbound-only
 * handler into a duplexer that can participate in the unified pipeline.</p>
 * <p>The inbound side is handled by the decoder, while the outbound side remains a transparent
 * pass-through. This makes it suitable for protocol steps that only rewrite the receive chain and
 * leave the send chain unchanged.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-10-02
 */
class ProtoDecoderDuplexWrap<RCV_UP, RCV_DOWN, SND> implements ProtoDuplex<RCV_UP, RCV_DOWN, SND, SND> {
    private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;

    ProtoDecoderDuplexWrap(ProtoHandler<RCV_UP, RCV_DOWN> decoder) {
        this.decoder = decoder;
    }

    /** {@inheritDoc} */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
    }

    /** {@inheritDoc} */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        return !isRcv || this.decoder.onEvent(context, event);
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND> sndUp, ProtoSndQueue<SND> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
            return ProtoStatus.Next;
        }
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return ProtoStatus.Next;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
    }
}
