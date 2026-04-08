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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * HTTP/3 语义编码器，用于把标准 {@link HttpObject} 实例转换为带 QPACK 压缩头的 {@link Http3Frame} 对象。
 * <p>
 * 该编码器负责伪头映射、QPACK 头压缩、请求 stream ID 分配、响应 stream ID 复用以及 FIN 标记管理。
 * 生成的 {@link Http3Frame} 随后会由 {@link Http3FrameEncoder} 序列化为线格式。
 * <p>
 * <b>编码路径：</b>{@code HttpObject → Http3Frame → ByteBuf}
 * @see Http3FrameEncoder
 * @see Http3Frame
 */
public class Http3HttpToFrameEncoder implements ProtoHandler<HttpObject, Http3Frame> {
    private static final Logger     logger = Logger.getLogger(Http3HttpToFrameEncoder.class);
    private final        HttpScheme scheme = HttpScheme.HTTPS;
    private final        boolean    serverMode;
    private final        int        maxTableSize;

    /**
     * 使用默认 QPACK settings 创建一个新的 HTTP/3 语义编码器。
     * @param serverMode 为 {@code true} 表示服务端模式，否则为客户端模式
     */
    public Http3HttpToFrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * 使用自定义 QPACK settings 创建一个新的 HTTP/3 语义编码器。
     * @param serverMode 为 {@code true} 表示服务端模式，否则为客户端模式
     * @param maxTableSize QPACK 动态表最大容量，单位为字节
     */
    public Http3HttpToFrameEncoder(boolean serverMode, int maxTableSize) {
        this.serverMode = serverMode;
        this.maxTableSize = maxTableSize;
    }

    @Override
    /**
     * 初始化编码器所需的连接级状态。
     */ public void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
        context.context(Http3EncoderContent.class, new Http3EncoderContent(serverMode, maxTableSize));
    }

    @Override
    /**
     * 将出站 HttpObject 编码为 Http3Frame。
     */ public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http3Frame> dst) throws Throwable {
        Http3EncoderContent state = context.context(Http3EncoderContent.class);
        boolean isPrintLog = context.getConfig().isPrintLog();

        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpResponse) {
                encodeFullResponse(context, state, (FullHttpResponse) msg, dst, isPrintLog);
            } else if (msg instanceof FullHttpRequest) {
                encodeFullRequest(context, state, (FullHttpRequest) msg, dst, isPrintLog);
            } else if (msg instanceof HttpResponse) {
                encodeResponse(context, state, (HttpResponse) msg, dst, isPrintLog);
            } else if (msg instanceof HttpRequest) {
                encodeRequest(context, state, (HttpRequest) msg, dst, isPrintLog);
            } else if (msg instanceof HttpHeaders) {
                encodeHeaders(context, state, (HttpHeaders) msg, dst, isPrintLog);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(context, state, (LastHttpContent) msg, dst, isPrintLog);
            } else if (msg instanceof HttpContent) {
                encodeContent(context, state, (HttpContent) msg, dst, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * 将完整 HTTP 请求（头 + 体）编码为 HEADERS + DATA frame。
     */
    private void encodeFullRequest(ProtoContext context, Http3EncoderContent state, FullHttpRequest request, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        if (!(request instanceof DefaultFullHttpRequest)) {
            throw new IllegalArgumentException("FullHttpRequest must be DefaultFullHttpRequest");
        }
        DefaultFullHttpRequest fullRequest = (DefaultFullHttpRequest) request;
        long streamId = state.allocateNextStreamId();

        // 使用 QPACK 编码请求头。
        state.beginHeaderEncode();
        state.encodeHeader(":method", request.method().name());
        state.encodeHeader(":path", request.uri());
        state.encodeHeader(":scheme", scheme.name());
        String host = fullRequest.getString(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            state.encodeHeader(":authority", host);
        }
        encodeNonPseudoHeaders(state, fullRequest);
        byte[] headerBlock = state.finishHeaderEncode();

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            dst.offerMessage(Http3Frame.headers(streamId, true, headerBlock));
        } else {
            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            dst.offerMessage(Http3Frame.headers(streamId, false, headerBlock));
            dst.offerMessage(Http3Frame.data(streamId, true, bodyBytes));
        }

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " FULL-REQUEST stream=" + streamId + " " + request.method() + " " + request.uri() + " headers=" + headerBlock.length + "B" + (hasBody ? " body=" + body.readableBytes() + "B" : ""));
        }
    }

    /**
     * 将完整 HTTP 响应（头 + 体）编码为对应的 HTTP/3 frame。
     */
    private void encodeFullResponse(ProtoContext context, Http3EncoderContent state, FullHttpResponse response, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        if (!(response instanceof DefaultFullHttpResponse)) {
            throw new IllegalArgumentException("FullHttpResponse must be DefaultFullHttpResponse");
        }
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) response;
        state.consumeResponseStreamId();
        long streamId = state.currentStreamId();

        state.beginHeaderEncode();
        state.encodeHeader(":status", String.valueOf(response.status().code()));
        encodeNonPseudoHeaders(state, fullResponse);
        byte[] headerBlock = state.finishHeaderEncode();

        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            dst.offerMessage(Http3Frame.headers(streamId, true, headerBlock));
        } else {
            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            dst.offerMessage(Http3Frame.headers(streamId, false, headerBlock));
            dst.offerMessage(Http3Frame.data(streamId, true, bodyBytes));
        }

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " FULL-RESPONSE stream=" + streamId + " status=" + response.status().code() + " headers=" + headerBlock.length + "B" + (hasBody ? " body=" + body.readableBytes() + "B" : ""));
        }
    }

    /**
     * 记录仅包含头部的 HTTP 请求起始信息，等待后续 {@link HttpHeaders} 输出 HEADERS frame。
     */
    private void encodeRequest(ProtoContext context, Http3EncoderContent state, HttpRequest request, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        long streamId = state.allocateNextStreamId();
        state.beginRequest(streamId, request.method().name(), request.uri(), scheme.name());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " REQUEST stream=" + streamId + " " + request.method() + " " + request.uri());
        }
    }

    /**
     * 记录仅包含头部的 HTTP 响应起始信息，等待后续 {@link HttpHeaders} 输出 HEADERS frame。
     */
    private void encodeResponse(ProtoContext context, Http3EncoderContent state, HttpResponse response, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        state.consumeResponseStreamId();
        long streamId = state.currentStreamId();
        state.beginResponse(streamId, response.status().code());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " RESPONSE stream=" + streamId + " status=" + response.status().code());
        }
    }

    /**
     * 把当前暂存的请求头或响应头编码为一个 HEADERS frame。
     */
    private void encodeHeaders(ProtoContext context, Http3EncoderContent state, HttpHeaders headers, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        if (!state.hasPendingRequest() && !state.hasPendingResponse()) {
            return;
        }

        state.beginHeaderEncode();
        if (state.hasPendingRequest()) {
            state.encodeHeader(":method", state.pendingMethod());
            state.encodeHeader(":path", state.pendingPath());
            state.encodeHeader(":scheme", state.pendingScheme());

            String host = headers.getString(HttpHeaderNames.HOST);
            if (StringUtils.isNotBlank(host)) {
                state.encodeHeader(":authority", host);
            }
        } else {
            state.encodeHeader(":status", String.valueOf(state.pendingStatus()));
        }
        encodeNonPseudoHeaders(state, headers);

        int headerBlockLen = state.headerEncodedLength();
        dst.offerMessage(Http3Frame.headers(state.currentStreamId(), false, state.headerEncodedBuffer(), 0, headerBlockLen));
        state.clearPendingHeaders();

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " HEADERS stream=" + state.currentStreamId() + " count=" + headers.headerSize());
        }
    }

    /**
     * 将消息体内容编码为 DATA frame。
     */
    private void encodeContent(ProtoContext context, Http3EncoderContent state, HttpContent content, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        long streamId = state.currentStreamId();
        int bodyLen = body.readableBytes();
        byte[] bodyBytes = new byte[bodyLen];
        body.getBytes(0, bodyBytes, 0, bodyLen);

        dst.offerMessage(Http3Frame.data(streamId, false, bodyBytes));

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " DATA stream=" + streamId + " len=" + bodyLen);
        }
    }

    /**
     * 将最后一段内容编码为带 FIN 的 DATA frame。
     */
    private void encodeLastContent(ProtoContext context, Http3EncoderContent state, LastHttpContent content, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        long streamId = state.currentStreamId();
        ByteBuf body = content.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            dst.offerMessage(Http3Frame.data(streamId, true, bodyBytes));
        } else {
            dst.offerMessage(Http3Frame.data(streamId, true, new byte[0]));
        }

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " LAST-DATA stream=" + streamId + " len=" + bodyLen);
        }
    }

    /**
     * 将非伪头字段编码进 QPACK 编码器。
     */
    private void encodeNonPseudoHeaders(Http3EncoderContent state, HttpHeaders src) {
        if (src == null || src.headerSize() == 0) {
            return;
        }
        for (String headerName : src.headerNames()) {
            if (!StringUtils.startsWith(headerName, ":") && !StringUtils.equalsIgnoreCase(headerName, HttpHeaderNames.HOST)) {
                for (String value : src.getValues(headerName)) {
                    state.encodeHeader(headerName.toLowerCase(), value);
                }
            }
        }
    }
}
