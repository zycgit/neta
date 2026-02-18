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
package net.hasor.neta.codec.http;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * A client-side HTTP codec that combines {@link HttpResponseDecoder} and
 * {@link HttpRequestEncoder} into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf → HttpObject (response decoding)
 * SND direction: HttpObject → ByteBuf (request encoding)
 * <p>
 * This is the Neta equivalent of Netty's {@code HttpClientCodec}.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("http", new HttpClientCodec());
 * </pre>
 */
public class HttpClientDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {

    private final HttpResponseDecoder decoder;
    private final HttpRequestEncoder  encoder;

    /** Creates a client codec with default decoder limits. */
    public HttpClientDuplexe() {
        this.decoder = new HttpResponseDecoder();
        this.encoder = new HttpRequestEncoder();
    }

    /**
     * Creates a client codec with the specified decoder limits.
     * @param maxInitialLineLength maximum length of the status-line
     * @param maxHeaderSize maximum total size of all headers
     * @param maxChunkSize maximum chunk size for content delivery
     */
    public HttpClientDuplexe(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this.decoder = new HttpResponseDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        this.encoder = new HttpRequestEncoder();
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return encoder.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        decoder.onInit(context);
        encoder.onInit(context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        decoder.onActive(context);
        encoder.onActive(context);
    }

    @Override
    public void onClose(ProtoContext context) {
        decoder.onClose(context);
        encoder.onClose(context);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return decoder.onError(context, e, eh);
        } else {
            return encoder.onError(context, e, eh);
        }
    }
}
