/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * HTTP/3 frame-layer duplex codec that combines binary frame decoding and frame encoding.
 * <p>
 * Pipeline view:
 * <pre>
 * inbound: socket bytes -> Http3FrameDuplex -> Http3Frame
 * outbound: Http3Frame -> Http3FrameDuplex -> socket bytes
 * </pre>
 * <p>
 * Typical semantic usage:
 * <pre>
 * Http3Settings settings = Http3Settings.defaultLocalSettings(true);
 * ctx.addLast("h3-frame", new Http3FrameDuplex(true, settings));
 * ctx.addLast("h3-object", new Http3ObjectDuplex(true, settings));
 * </pre>
 */
public class Http3FrameDuplex implements ProtoDuplex<ByteBuf, Http3Frame, Http3Frame, ByteBuf> {
    private final Http3FrameDecoder decoder;
    private final Http3FrameEncoder encoder;

    public Http3FrameDuplex() {
        this(false, Http3Settings.defaultLocalSettings(false));
    }

    public Http3FrameDuplex(boolean serverMode) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode));
    }

    public Http3FrameDuplex(boolean serverMode, Http3Settings localSettings) {
        Http3Settings settings = localSettings != null ? new Http3Settings(localSettings) : Http3Settings.defaultLocalSettings(serverMode);
        this.decoder = new Http3FrameDecoder(serverMode, settings);
        this.encoder = new Http3FrameEncoder(settings);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.decoder.onInit(name, rcvSize, context);
        this.encoder.onInit(name, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,           //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Http3Frame> rcvDown,    //
            ProtoRcvQueue<Http3Frame> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.encoder.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.decoder.onError(context, e, eh);
        } else {
            return this.encoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.decoder.onClose(context);
        this.encoder.onClose(context);
    }
}
