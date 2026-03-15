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
package net.hasor.neta.codec.http.h2;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * A client-side HTTP/2 codec that combines frame-level and semantic-level
 * handlers into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf →[FrameDecoder]→ Http2Frame →[FrameToMessageDecoder]→ Http2Message<br>
 * SND direction: Http2Message →[MessageToFrameEncoder]→ Http2Frame →[FrameEncoder]→ ByteBuf
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h2", new Http2ClientDuplexe());
 * </pre>
 */
public class Http2ClientDuplexe implements ProtoDuplexer<ByteBuf, Http2Message, Http2Message, ByteBuf> {
    private final Http2FrameDecoder          frameDecoder;
    private final Http2FrameToMessageDecoder frameToMessageDecoder;
    private final Http2MessageToFrameEncoder messageToFrameEncoder;
    private final Http2FrameEncoder          frameEncoder;
    private final Http2FrameBridgeQueue      bridgeQueue        = new Http2FrameBridgeQueue();
    private final Http2MessageBridgeQueue    messageBridgeQueue = new Http2MessageBridgeQueue();

    /** Creates a client-side HTTP/2 codec with default HPACK settings. */
    public Http2ClientDuplexe() {
        this.frameDecoder = new Http2FrameDecoder(false);
        this.frameToMessageDecoder = new Http2FrameToMessageDecoder(false);
        this.messageToFrameEncoder = new Http2MessageToFrameEncoder(false);
        this.frameEncoder = new Http2FrameEncoder();
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(name, rcvSize, context);
        this.frameToMessageDecoder.onInit(name, rcvSize, context);
        this.messageToFrameEncoder.onInit(name, sndSize, context);
        this.frameEncoder.onInit(name, sndSize, context);
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        context.context(Http2Context.class, new Http2ContextImpl(false, decoderState));
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.frameDecoder.onActive(context);
        this.frameToMessageDecoder.onActive(context);
        this.messageToFrameEncoder.onActive(context);
        this.frameEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<Http2Message> rcvDown,//
            ProtoRcvQueue<Http2Message> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV: ByteBuf → Http2Frame → Http2Message
            this.bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, this.bridgeQueue);
            this.frameToMessageDecoder.onMessage(context, this.bridgeQueue, rcvDown);
            return ProtoStatus.Next;
        } else {
            // SND: Http2Message → Http2Frame → ByteBuf
            this.bridgeQueue.clear();
            this.messageBridgeQueue.clear();
            this.messageBridgeQueue.offerMessage(sndUp);
            this.messageToFrameEncoder.onMessage(context, this.messageBridgeQueue, this.bridgeQueue);
            this.frameEncoder.onMessage(context, this.bridgeQueue, sndDown);
            return ProtoStatus.Next;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onError(context, e, eh);
        } else {
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.frameDecoder.onClose(context);
        this.frameToMessageDecoder.onClose(context);
        this.messageToFrameEncoder.onClose(context);
        this.frameEncoder.onClose(context);
    }
}
