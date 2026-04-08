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
 * 客户端侧的 HTTP/3 编解码器，将 frame 层与语义层处理器组合为一个双向处理节点。
 * <p>
 * RCV 方向：ByteBuf →[FrameDecoder]→ Http3Frame →[FrameToHttpDecoder]→ HttpObject<br>
 * SND 方向：HttpObject →[HttpToFrameEncoder]→ Http3Frame →[FrameEncoder]→ ByteBuf
 * <p>
 * 输出的 {@link HttpObject} 类型与 HTTP/1.x 和 HTTP/2 编解码器保持一致，从而支持协议无关的应用逻辑。
 * <p>pipeline 用法：</p>
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

    /**
     * 使用默认 QPACK settings 创建客户端侧 HTTP/3 编解码器。
     */
    public Http3ClientDuplexe() {
        this.frameDecoder = new Http3FrameDecoder(false);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(false);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(false);
        this.frameEncoder = new Http3FrameEncoder();
    }

    /**
     * 使用自定义 QPACK settings 创建客户端侧 HTTP/3 编解码器。
     * @param maxTableSize QPACK 动态表最大容量，单位为字节，默认值通常为 4096
     * @param maxHeaderListSize 已解码头字段允许的最大总大小，默认值通常为 65536
     */
    public Http3ClientDuplexe(int maxTableSize, int maxHeaderListSize) {
        this.frameDecoder = new Http3FrameDecoder(false);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(false, maxTableSize, maxHeaderListSize);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(false, maxTableSize);
        this.frameEncoder = new Http3FrameEncoder();
    }

    @Override
    /**
     * 初始化收发两侧的 HTTP/3 编解码链，并注册 Http3Context。
     */ public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.frameDecoder.onInit(name, rcvSize, context);
        this.frameToHttpDecoder.onInit(name, rcvSize, context);
        this.httpToFrameEncoder.onInit(name, sndSize, context);
        this.frameEncoder.onInit(name, sndSize, context);
        Http3DecoderContent decoderContent = context.context(Http3DecoderContent.class);
        context.context(Http3Context.class, new Http3ContextImpl(false, decoderContent));
    }

    @Override
    /**
     * 传播激活事件到内部各处理器。
     */ public void onActive(ProtoContext context) throws Throwable {
        this.frameDecoder.onActive(context);
        this.frameToHttpDecoder.onActive(context);
        this.httpToFrameEncoder.onActive(context);
        this.frameEncoder.onActive(context);
    }

    @Override
    /**
     * 按收发方向执行 HTTP/3 编解码流程。
     */ public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            // RCV：ByteBuf → Http3Frame → HttpObject。
            this.bridgeQueue.clear();
            this.frameDecoder.onMessage(context, rcvUp, this.bridgeQueue);
            this.frameToHttpDecoder.onMessage(context, this.bridgeQueue, rcvDown);
            return ProtoStatus.Next;
        } else {
            // SND：HttpObject → Http3Frame → ByteBuf。
            this.bridgeQueue.clear();
            this.httpToFrameEncoder.onMessage(context, sndUp, this.bridgeQueue);
            this.frameEncoder.onMessage(context, this.bridgeQueue, sndDown);
            return ProtoStatus.Next;
        }
    }

    @Override
    /**
     * 按方向分发错误处理。
     */ public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onError(context, e, eh);
        } else {
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    /**
     * 关闭内部各处理器。
     */ public void onClose(ProtoContext context) {
        this.frameDecoder.onClose(context);
        this.frameToHttpDecoder.onClose(context);
        this.httpToFrameEncoder.onClose(context);
        this.frameEncoder.onClose(context);
    }
}
