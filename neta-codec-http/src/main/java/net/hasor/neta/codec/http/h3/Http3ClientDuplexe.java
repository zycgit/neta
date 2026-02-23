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
package net.hasor.neta.codec.http.h3;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * A client-side HTTP/3 codec that combines {@link Http3FrameDecoder} and
 * {@link Http3FrameEncoder} into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf → HttpObject (HTTP/3 frame decoding via QPACK → HttpResponse/HttpContent)
 * SND direction: HttpObject → ByteBuf (HttpRequest/HttpContent → HTTP/3 frame encoding via QPACK)
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * and HTTP/2 codecs, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h3", new Http3ClientDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http3ClientDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private final Http3FrameDecoder decoder;
    private final Http3FrameEncoder encoder;

    /** Creates a client-side HTTP/3 codec with default QPACK settings. */
    public Http3ClientDuplexe() {
        this.decoder = new Http3FrameDecoder(false);
        this.encoder = new Http3FrameEncoder(false);
    }

    /**
     * Creates a client-side HTTP/3 codec with custom QPACK settings.
     * @param maxTableSize maximum QPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 65536)
     */
    public Http3ClientDuplexe(int maxTableSize, int maxHeaderListSize) {
        this.decoder = new Http3FrameDecoder(false, maxTableSize, maxHeaderListSize);
        this.encoder = new Http3FrameEncoder(false, maxTableSize);
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.decoder.onInit(context);
        this.encoder.onInit(context);
        context.context(Http3Context.class, this.decoder.createContext());
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
