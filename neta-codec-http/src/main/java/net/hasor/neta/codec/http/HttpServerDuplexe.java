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
import net.hasor.neta.codec.http.websocket.WebSocketFrameDecoder;
import net.hasor.neta.codec.http.websocket.WebSocketFrameEncoder;

/**
 * A server-side HTTP codec that combines {@link HttpRequestDecoder} and
 * {@link HttpResponseEncoder} into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf → HttpObject (request decoding)
 * SND direction: HttpObject → ByteBuf (response encoding)
 * <p>
 * After a WebSocket upgrade (101 Switching Protocols), this codec automatically
 * switches to {@link WebSocketFrameDecoder} / {@link WebSocketFrameEncoder}
 * for subsequent frame-based communication.
 * <p>
 * This is the Neta equivalent of Netty's {@code HttpServerCodec}.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("http", new HttpServerCodec());
 * </pre>
 */
public class HttpServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {

    private final HttpRequestDecoder  decoder;
    private final HttpResponseEncoder encoder;
    // WebSocket upgrade state
    private       WebSocketFrameDecoder wsDecoder;
    private       WebSocketFrameEncoder wsEncoder;
    private       boolean               upgraded = false;

    /** Creates a server codec with default decoder limits. */
    public HttpServerDuplexe() {
        this.decoder = new HttpRequestDecoder();
        this.encoder = new HttpResponseEncoder();
    }

    /**
     * Creates a server codec with the specified decoder limits.
     * @param maxInitialLineLength maximum length of the request-line
     * @param maxHeaderSize maximum total size of all headers
     * @param maxChunkSize maximum chunk size for content delivery
     */
    public HttpServerDuplexe(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this.decoder = new HttpRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        this.encoder = new HttpResponseEncoder();
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.decoder.onInit(context);
        this.encoder.onInit(context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.decoder.onActive(context);
        this.encoder.onActive(context);
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            if (this.upgraded) {
                // WebSocket mode: decode raw bytes into WebSocketFrame objects
                return ((ProtoHandler) this.wsDecoder).onMessage(context, rcvUp, rcvDown);
            }
            return this.decoder.onMessage(context, rcvUp, rcvDown);
        } else {
            if (this.upgraded) {
                // WebSocket mode: encode WebSocketFrame objects into raw bytes
                return ((ProtoHandler) this.wsEncoder).onMessage(context, sndUp, sndDown);
            }
            // Check if the outgoing message is a WebSocket upgrade (101 Switching Protocols)
            boolean shouldUpgrade = false;
            Object peek = sndUp.peekMessage();
            if (peek instanceof FullHttpResponse) {
                FullHttpResponse resp = (FullHttpResponse) peek;
                if (resp.status().code() == 101) {
                    String upgradeHeader = resp.headers().get("Upgrade");
                    if (upgradeHeader != null && upgradeHeader.equalsIgnoreCase("websocket")) {
                        shouldUpgrade = true;
                    }
                }
            }
            // Encode the response with the HTTP encoder
            ProtoStatus status = this.encoder.onMessage(context, sndUp, sndDown);
            // Switch to WebSocket codec after the 101 response has been encoded
            if (shouldUpgrade) {
                this.upgraded = true;
                this.wsDecoder = new WebSocketFrameDecoder();
                this.wsEncoder = new WebSocketFrameEncoder();
            }
            return status;
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
        if (this.wsDecoder != null) {
            this.wsDecoder.onClose(context);
        }
    }

    /** Returns true if this codec has been upgraded to WebSocket mode. */
    public boolean isUpgraded() {
        return this.upgraded;
    }
}
