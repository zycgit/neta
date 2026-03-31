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

/**
 * Bidirectional message-layer codec for post-handshake WebSocket traffic.
 * <p>
 * Pairs {@link WebSocketInboundHandler} and {@link WebSocketOutboundHandler} so business code can
 * work with {@link WebSocketMessage} instead of raw frames.
 */
public class WebSocketMessageDuplexer implements ProtoDuplexer<WebSocketFrame, WebSocketMessage, WebSocketMessage, WebSocketFrame> {
    private final WebSocketInboundHandler  inbound;
    private final WebSocketOutboundHandler outbound;

    public WebSocketMessageDuplexer() {
        this.inbound = new WebSocketInboundHandler();
        this.outbound = new WebSocketOutboundHandler();
    }

    public WebSocketMessageDuplexer(boolean aggregateFragments) {
        this.inbound = new WebSocketInboundHandler(aggregateFragments);
        this.outbound = new WebSocketOutboundHandler();
    }

    public WebSocketMessageDuplexer(boolean aggregateFragments, int maxMessagePayloadLength) {
        this.inbound = new WebSocketInboundHandler(aggregateFragments, maxMessagePayloadLength);
        this.outbound = new WebSocketOutboundHandler();
    }

    public WebSocketMessageDuplexer(int maxFramePayloadLength) {
        this.inbound = new WebSocketInboundHandler();
        this.outbound = new WebSocketOutboundHandler(maxFramePayloadLength);
    }

    public WebSocketMessageDuplexer(boolean aggregateFragments, int maxMessagePayloadLength, int maxFramePayloadLength) {
        this.inbound = new WebSocketInboundHandler(aggregateFragments, maxMessagePayloadLength);
        this.outbound = new WebSocketOutboundHandler(maxFramePayloadLength);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.inbound.onInit(name, rcvSize, context);
        this.outbound.onInit(name, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.inbound.onActive(context);
        this.outbound.onActive(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.inbound.onEvent(context, event);
        } else {
            return this.outbound.onEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,                    //
            ProtoRcvQueue<WebSocketFrame> rcvUp, ProtoSndQueue<WebSocketMessage> rcvDown,//
            ProtoRcvQueue<WebSocketMessage> sndUp, ProtoSndQueue<WebSocketFrame> sndDown) throws Throwable {
        if (isRcv) {
            return this.inbound.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.outbound.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.inbound.onError(context, e, eh);
        } else {
            return this.outbound.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.inbound.onClose(context);
        this.outbound.onClose(context);
    }
}