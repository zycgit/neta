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
package net.hasor.neta.codec.http2;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * A server-side HTTP/2 codec that combines {@link Http2FrameDecoder} and
 * {@link Http2FrameEncoder} into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf → HttpObject (HTTP/2 frame decoding → HttpRequest/HttpContent)
 * SND direction: HttpObject → ByteBuf (HttpResponse/HttpContent → HTTP/2 frame encoding)
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * codec, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h2", new Http2ServerDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http2ServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {

    private final Http2FrameDecoder decoder;
    private final Http2FrameEncoder encoder;

    /** Creates a server-side HTTP/2 codec. */
    public Http2ServerDuplexe() {
        this.decoder = new Http2FrameDecoder(true);
        this.encoder = new Http2FrameEncoder(true);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.decoder.onInit(context);
        this.encoder.onInit(context);
        context.context(Http2Context.class, this.decoder.createContext());
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
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
