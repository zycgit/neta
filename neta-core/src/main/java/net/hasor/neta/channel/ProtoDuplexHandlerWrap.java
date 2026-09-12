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
 * Internal wrapper that combines one decoder and one encoder into a single duplexer.
 * <p>When the upper-level API declares a protocol step as an encoder/decoder pair, this wrapper
 * merges the two unidirectional handlers into one duplexer that can be inserted into the pipeline.</p>
 * <p>Inbound calls are delegated to the decoder and outbound calls are delegated to the encoder,
 * so the wrapper behaves externally as one complete bidirectional protocol step.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-27
 * @see ProtoDuplex
 */
class ProtoDuplexHandlerWrap<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> implements ProtoDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private final ProtoHandler<RCV_UP, RCV_DOWN> decoder;
    private final ProtoHandler<SND_UP, SND_DOWN> encoder;

    ProtoDuplexHandlerWrap(ProtoHandler<RCV_UP, RCV_DOWN> decoder, ProtoHandler<SND_UP, SND_DOWN> encoder) {
        this.decoder = decoder;
        this.encoder = encoder;
    }

    /** {@inheritDoc} */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
    }

    /** {@inheritDoc} */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoSndQueue<RCV_DOWN> rcvDown, ProtoRcvQueue<SND_UP> sndUp, ProtoSndQueue<SND_DOWN> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    /** {@inheritDoc} */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}
