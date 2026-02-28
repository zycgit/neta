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
import net.hasor.neta.codec.http.HttpObject;

/**
 * A client-side HTTP/2 codec that combines frame-level and semantic-level
 * handlers into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf →[FrameDecoder]→ Http2Frame →[FrameToHttpDecoder]→ HttpObject<br>
 * SND direction: HttpObject →[HttpToFrameEncoder]→ Http2Frame →[FrameEncoder]→ ByteBuf
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * codec, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h2", new Http2ClientDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http2ClientDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private final Http2FrameDecoder       frameDecoder;
    private final Http2FrameToHttpDecoder frameToHttpDecoder;
    private final Http2HttpToFrameEncoder httpToFrameEncoder;
    private final Http2FrameEncoder       frameEncoder;
    private final Http2FrameBridgeQueue   bridgeQueue = new Http2FrameBridgeQueue();

    /** Creates a client-side HTTP/2 codec with default HPACK settings. */
    public Http2ClientDuplexe() {
        this.frameDecoder = new Http2FrameDecoder(false);
        this.frameToHttpDecoder = new Http2FrameToHttpDecoder(false);
        this.httpToFrameEncoder = new Http2HttpToFrameEncoder(false);
        this.frameEncoder = new Http2FrameEncoder();
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(context);
        this.frameToHttpDecoder.onInit(context);
        this.httpToFrameEncoder.onInit(context);
        this.frameEncoder.onInit(context);
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        context.context(Http2Context.class, new Http2ContextImpl(false, decoderState));
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.frameDecoder.onActive(context);
        this.frameToHttpDecoder.onActive(context);
        this.httpToFrameEncoder.onActive(context);
        this.frameEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV: ByteBuf → Http2Frame → HttpObject
            this.bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, this.bridgeQueue);
            this.frameToHttpDecoder.onMessage(context, this.bridgeQueue, rcvDown);
            return ProtoStatus.Next;
        } else {
            // SND: HttpObject → Http2Frame → ByteBuf
            this.bridgeQueue.clear();
            this.httpToFrameEncoder.onMessage(context, sndUp, this.bridgeQueue);
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
        this.frameToHttpDecoder.onClose(context);
        this.httpToFrameEncoder.onClose(context);
        this.frameEncoder.onClose(context);
    }
}
