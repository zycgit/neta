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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.quic.QuicStreamChannel;
import net.hasor.neta.codec.http.HttpObject;

/**
 * 服务端侧的 HTTP/3 编解码器，将 frame 层与语义层处理器组合为一个双向处理节点。
 * <p>
 * RCV 方向：ByteBuf →[FrameDecoder]→ Http3Frame →[FrameToHttpDecoder]→ HttpObject<br>
 * SND 方向：HttpObject →[HttpToFrameEncoder]→ Http3Frame →[FrameEncoder]→ ByteBuf
 * <p>
 * 输出的 {@link HttpObject} 类型与 HTTP/1.x 和 HTTP/2 编解码器保持一致，从而支持协议无关的应用逻辑。
 * <p>pipeline 用法：</p>
 * <pre>
 *   ctx.addLast("h3", new Http3ServerDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpRequestAggregator(1048576));
 * </pre>
 */
public class Http3ServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private static final Logger                  logger      = Logger.getLogger(Http3ServerDuplexe.class);
    private final        Http3FrameDecoder       frameDecoder;
    private final        Http3FrameToHttpDecoder frameToHttpDecoder;
    private final        Http3HttpToFrameEncoder httpToFrameEncoder;
    private final        Http3FrameEncoder       frameEncoder;
    private final        Http3FrameBridgeQueue   bridgeQueue = new Http3FrameBridgeQueue();

    /**
     * 使用默认 QPACK settings 创建服务端侧 HTTP/3 编解码器。
     */
    public Http3ServerDuplexe() {
        this.frameDecoder = new Http3FrameDecoder(true);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(true);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(true);
        this.frameEncoder = new Http3FrameEncoder();
    }

    /**
     * 使用自定义 QPACK settings 创建服务端侧 HTTP/3 编解码器。
     * @param maxTableSize QPACK 动态表最大容量，单位为字节
     * @param maxHeaderListSize 已解码头字段允许的最大总大小
     */
    public Http3ServerDuplexe(int maxTableSize, int maxHeaderListSize) {
        this.frameDecoder = new Http3FrameDecoder(true);
        this.frameToHttpDecoder = new Http3FrameToHttpDecoder(true, maxTableSize, maxHeaderListSize);
        this.httpToFrameEncoder = new Http3HttpToFrameEncoder(true, maxTableSize);
        this.frameEncoder = new Http3FrameEncoder();
    }

    /**
     * 将语义层 {@link Http3ResetEvent} 的错误码哨兵值映射为对应的 HTTP/3 应用错误码，见 RFC 9114 第 8.1 节。
     */
    private static long resolveH3ErrorCode(long code) {
        if (code == Http3ResetEvent.CANCEL) {
            return Http3ErrorCode.H3_REQUEST_CANCELLED;
        } else if (code == Http3ResetEvent.INTERNAL_ERROR) {
            return Http3ErrorCode.H3_INTERNAL_ERROR;
        } else if (code == Http3ResetEvent.REFUSED) {
            return Http3ErrorCode.H3_REQUEST_REJECTED;
        } else if (code < 0) {
            return Http3ErrorCode.H3_INTERNAL_ERROR; // 未知哨兵值统一映射为 H3_INTERNAL_ERROR。
        }
        return code;
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
        context.context(Http3Context.class, new Http3ContextImpl(true, decoderContent));
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
            // 从解码器的 FIFO 队列中提取正确的 stream ID，确保响应关联到对应请求 stream。
            Http3DecoderContent decoderContent = context.context(Http3DecoderContent.class);
            long nextStreamId = decoderContent.pollResponseStreamId();
            if (nextStreamId >= 0) {
                Http3EncoderContent encoderContent = context.context(Http3EncoderContent.class);
                encoderContent.setResponseStreamId(nextStreamId);
            }

            // SND：HttpObject → Http3Frame → ByteBuf。
            this.bridgeQueue.clear();
            this.httpToFrameEncoder.onMessage(context, sndUp, this.bridgeQueue);
            this.frameEncoder.onMessage(context, this.bridgeQueue, sndDown);

            return ProtoStatus.Next;
        }
    }

    @Override
    /**
     * 按方向处理错误。发送侧出错时，会尽量退化为当前 stream 的 QUIC RESET_STREAM。
     */ public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.frameDecoder.onError(context, e, eh);
        } else {
            // 发送侧错误：尽量只对当前 stream 发送 QUIC RESET_STREAM。
            SoChannel<?> channel = context.getChannel();
            if (channel instanceof QuicStreamChannel) {
                try {
                    Http3DecoderContent decoderState = context.context(Http3DecoderContent.class);
                    Http3EncoderContent encoderState = context.context(Http3EncoderContent.class);
                    long streamId = encoderState != null ? encoderState.responseStreamId() : -1L;
                    if (streamId >= 0) {
                        decoderState.closeStream(streamId);
                        decoderState.removeFromResponseQueue(streamId);
                    }
                    ((QuicStreamChannel) channel).sendReset(Http3ErrorCode.H3_INTERNAL_ERROR, 0L);
                    logger.warn("[H3-SND] ch=" + channel.getChannelId() + " encoding error, sent RESET_STREAM(H3_INTERNAL_ERROR): " + e.getMessage());
                    return ProtoStatus.Next;
                } catch (Throwable t) {
                    // 如果发送 RESET_STREAM 失败，则继续走传输层默认错误处理。
                }
            }
            return this.frameEncoder.onError(context, e, eh);
        }
    }

    @Override
    /**
     * 处理 HTTP/3 相关事件。
     * 当前实现只消费 {@link Http3ResetEvent}。
     */ public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (event.getEventType() == Http3ResetEvent.class) {
            Http3ResetEvent reset = (Http3ResetEvent) event.getData();
            long streamId = reset.streamId();
            long errorCode = resolveH3ErrorCode(reset.errorCode());
            // 清理 stream 状态以及失配的响应队列条目。
            Http3DecoderContent decoderState = context.context(Http3DecoderContent.class);
            decoderState.closeStream(streamId);
            decoderState.removeFromResponseQueue(streamId);
            // 委托 QUIC 传输层发送 RESET_STREAM。
            SoChannel<?> channel = context.getChannel();
            if (channel instanceof QuicStreamChannel) {
                ((QuicStreamChannel) channel).sendReset(errorCode, 0L);
            }
            if (context.getConfig().isPrintLog()) {
                logger.info("[H3-SND] ch=" + channel.getChannelId() + " RESET_STREAM errorCode=0x" + Long.toHexString(errorCode) + " (via Event)");
            }
            return false; // 当前事件已消费。
        }
        return true;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

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
