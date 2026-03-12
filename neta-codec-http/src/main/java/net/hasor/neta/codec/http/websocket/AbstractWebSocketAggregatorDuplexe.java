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

abstract class AbstractWebSocketAggregatorDuplexe implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final WebSocketFrameDecoder       frameDecoder;
    private final WebSocketInboundAggregator  inboundAggregator;
    private final WebSocketOutboundAggregator outboundAggregator;
    private final WebSocketFrameEncoder       frameEncoder;

    protected AbstractWebSocketAggregatorDuplexe(WebSocketVersion version, boolean clientMode, int maxMessageSize) {
        this.frameDecoder = new WebSocketFrameDecoder(version);
        this.inboundAggregator = new WebSocketInboundAggregator(maxMessageSize);
        this.outboundAggregator = new WebSocketOutboundAggregator(version, clientMode);
        this.frameEncoder = new WebSocketFrameEncoder(version);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(name + "-frame-decoder", rcvSize, context);
        this.inboundAggregator.onInit(name + "-inbound-agg", rcvSize, context);
        this.outboundAggregator.onInit(name + "-outbound-agg", sndSize, context);
        this.frameEncoder.onInit(name + "-frame-encoder", sndSize, context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onUserEvent(context, event) &&//
                    this.inboundAggregator.onUserEvent(context, event);
        } else {
            return this.outboundAggregator.onUserEvent(context, event) &&//
                    this.frameEncoder.onUserEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            ProtoQueue<HttpObject> frameQueue = new ProtoQueue<>(-1);
            ProtoStatus decodeStatus = this.frameDecoder.onMessage(context, rcvUp, frameQueue);
            frameQueue.sndSubmit();
            ProtoStatus aggregateStatus = this.inboundAggregator.onMessage(context, frameQueue, rcvDown);
            return decodeStatus == ProtoStatus.Next ? aggregateStatus : decodeStatus;
        } else {
            ProtoQueue<HttpObject> frameQueue = new ProtoQueue<>(-1);
            ProtoStatus aggregateStatus = this.outboundAggregator.onMessage(context, sndUp, frameQueue);
            frameQueue.sndSubmit();
            ProtoStatus encodeStatus = this.frameEncoder.onMessage(context, frameQueue, sndDown);
            return aggregateStatus == ProtoStatus.Next ? encodeStatus : aggregateStatus;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            this.frameDecoder.onError(context, e, eh);
            return this.inboundAggregator.onError(context, e, eh);
        } else {
            this.outboundAggregator.onError(context, e, eh);
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.frameDecoder.onClose(context);
        this.inboundAggregator.onClose(context);
        this.outboundAggregator.onClose(context);
        this.frameEncoder.onClose(context);
    }
}