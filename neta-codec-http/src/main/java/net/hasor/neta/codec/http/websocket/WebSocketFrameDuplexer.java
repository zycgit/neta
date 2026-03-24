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
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Bidirectional frame codec for post-handshake WebSocket traffic.
 * <p>
 * Pairs {@link WebSocketFrameDecoder} and {@link WebSocketFrameEncoder} so frame-level pipelines
 * can be wired as one duplex node.
 */
public class WebSocketFrameDuplexer implements ProtoDuplexer<HttpObject, WebSocketFrame, WebSocketFrame, HttpObject> {
    private final WebSocketFrameDecoder decoder;
    private final WebSocketFrameEncoder encoder;

    public WebSocketFrameDuplexer() {
        this(WebSocketVersion.V13);
    }

    public WebSocketFrameDuplexer(WebSocketVersion version) {
        this(version, Integer.MAX_VALUE);
    }

    public WebSocketFrameDuplexer(WebSocketVersion version, int maxPayloadChunkLength) {
        this.decoder = new WebSocketFrameDecoder(version, maxPayloadChunkLength);
        this.encoder = new WebSocketFrameEncoder(version);
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
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.decoder.onUserEvent(context, event);
        } else {
            return this.encoder.onUserEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<WebSocketFrame> rcvDown,//
            ProtoRcvQueue<WebSocketFrame> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
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