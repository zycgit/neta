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
 * A client-side HTTP/3 codec that combines frame-level and semantic-level
 * handlers into a single bidirectional handler.
 * <p>
 * RCV direction: ByteBuf →[FrameDecoder]→ Http3Frame →[FrameToHttpDecoder]→ HttpObject<br>
 * SND direction: HttpObject →[HttpToFrameEncoder]→ Http3Frame →[FrameEncoder]→ ByteBuf
 * <p>
 * The output {@link HttpObject} types are identical to those produced by the HTTP/1.x
 * and HTTP/2 codecs, enabling protocol-agnostic application logic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLast("h3", new Http3ClientDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpResponseAggregator(1048576));
 * </pre>
 */
public class Http3ClientDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private final Http3FrameDecoder       frameDecoder;
    private final Http3FrameToHttpDecoder frameToHttpDecoder;
    private final Http3HttpToFrameEncoder httpToFrameEncoder;
    private final Http3FrameEncoder       frameEncoder;
    private final Http3FrameBridgeQueue   bridgeQueue = new Http3FrameBridgeQueue();

    /** Creates a client-side HTTP/3 codec with default QPACK settings. */
    public Http3ClientDuplexe() {
        this.frameDecoder = new Http3FrameDecoder(false);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(false);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(false);
        this.frameEncoder = new Http3FrameEncoder();
    }

    /**
     * Creates a client-side HTTP/3 codec with custom QPACK settings.
     * @param maxTableSize maximum QPACK dynamic table size in bytes (default: 4096)
     * @param maxHeaderListSize maximum total size of all decoded headers (default: 65536)
     */
    public Http3ClientDuplexe(int maxTableSize, int maxHeaderListSize) {
        this.frameDecoder = new Http3FrameDecoder(false);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(false, maxTableSize, maxHeaderListSize);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(false, maxTableSize);
        this.frameEncoder = new Http3FrameEncoder();
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(name, rcvSize, context);
        this.frameToHttpDecoder.onInit(name, rcvSize, context);
        this.httpToFrameEncoder.onInit(name, sndSize, context);
        this.frameEncoder.onInit(name, sndSize, context);
        Http3DecoderContent decoderContent = context.context(Http3DecoderContent.class);
        context.context(Http3Context.class, new Http3ContextImpl(false, decoderContent));
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
            // RCV: ByteBuf → Http3Frame → HttpObject
            this.bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, this.bridgeQueue);
            this.frameToHttpDecoder.onMessage(context, this.bridgeQueue, rcvDown);
            return ProtoStatus.Next;
        } else {
            // SND: HttpObject → Http3Frame → ByteBuf
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
