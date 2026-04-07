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
 * Client-side HTTP/1.x duplex codec that combines response decoding and request encoding.
 * <p>
 * This class packages {@link HttpResponseDecoder} and {@link HttpRequestEncoder} into a single
 * bidirectional pipeline node and serves as the standard entry point for HTTP/1.x client traffic.
 * <p>
 * It is also the first point on the client side where raw bytes are lifted into an
 * {@link HttpObject} stream. An "HttpObject stream" means an HTTP message is not forced into a
 * single object. Instead, it is split into an ordered sequence that follows protocol structure.
 * For example, a response typically arrives as a status line, a header block, an end-of-headers
 * marker, content chunks, optional trailing headers, and a final end marker.
 * <p>
 * Pipeline view:
 * <pre>
 *   inbound:  socket bytes -> HttpClientDuplexe -> HttpObject
 *   outbound: HttpObject   -> HttpClientDuplexe -> socket bytes
 * </pre>
 * <p>
 * Recommended usage falls into two scenarios depending on the processing goal:
 * <p>
 * Scenario 1: stream the response.
 * <pre>
 *   ctx.addLast("http", new HttpClientDuplexe());
 *   ctx.addLast("handler", responsePartHandler);
 * </pre>
 * This mode works directly with the {@link HttpObject} stream and suits incremental processing,
 * proxy forwarding, large downloads, SSE, or any case where you do not want to buffer the full
 * response first.
 * <p>
 * Scenario 2: aggregate the full response.
 * <pre>
 *   ctx.addLast("http", new HttpClientDuplexe());
 *   ctx.addLastDecoder("http-agg", new HttpResponseAggregator(1048576));
 *   ctx.addLast("handler", fullResponseHandler);
 * </pre>
 * Add the aggregator only when business logic explicitly needs a {@link FullHttpResponse}, such
 * as reading the entire response body at once, performing unified signature verification, or
 * mapping directly to an upper-layer object.
 * <p>
 * When {@link HttpThroughEvent} enables transparent mode, both inbound and outbound sides stop
 * interpreting HTTP frame semantics and forward the upgraded raw payload directly.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class HttpClientDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private final HttpResponseDecoder decoder;
    private final HttpRequestEncoder  encoder;

    /**
     * Creates a client codec with the default decoding limits.
     */
    public HttpClientDuplexe() {
        this.decoder = new HttpResponseDecoder();
        this.encoder = new HttpRequestEncoder();
    }

    /**
     * Creates a client codec with explicit decoding limits.
     * @param maxInitialLineLength the maximum length of the status line
     * @param maxHeaderSize the maximum total size allowed for all header fields
     * @param maxChunkSize the maximum output size of each content chunk
     */
    public HttpClientDuplexe(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this.decoder = new HttpResponseDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        this.encoder = new HttpRequestEncoder();
    }

    /**
     * Initializes the codecs on both inbound and outbound sides.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        decoder.onInit(name, rcvSize, context);
        encoder.onInit(name, sndSize, context);
    }

    /**
     * Activates the codecs on both inbound and outbound sides.
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        decoder.onActive(context);
        encoder.onActive(context);
    }

    /**
     * Dispatches events by direction and synchronizes transparent mode switching on both sides.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (event.getEventType() == HttpThroughEvent.class) {
            this.decoder.onEvent(context, event);
            this.encoder.onEvent(context, event);
            return true;
        }

        if (isRcv) {
            return this.decoder.onEvent(context, event);
        } else {
            return this.encoder.onEvent(context, event);
        }
    }

    /**
     * Processes messages according to direction.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            return decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            return encoder.onMessage(context, sndUp, sndDown);
        }
    }

    /**
     * Processes exceptions according to direction.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return decoder.onError(context, e, eh);
        } else {
            return encoder.onError(context, e, eh);
        }
    }

    /**
     * Closes and releases codec state on both inbound and outbound sides.
     */
    @Override
    public void onClose(ProtoContext context) {
        decoder.onClose(context);
        encoder.onClose(context);
    }
}
