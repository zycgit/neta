/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.quic.QuicVarInt;
import net.hasor.neta.codec.http.*;
/**
 * HTTP/3 语义解码器，用于把 {@link Http3Frame} 对象转换为标准 {@link HttpObject} 实例。
 * <p>
 * 该解码器负责 QPACK 头解压、HTTP 消息创建以及 stream 状态管理。双向 stream 上的
 * HEADERS 和 DATA 会产出 {@link HttpObject}。单向 stream 上的 SETTINGS 和 GOAWAY 会更新连接状态或发布事件。
 * <p>
 * <b>解码路径：</b>{@code ByteBuf → Http3Frame → HttpObject}
 * <p>
 * 所有解码结果都会以标准 {@link HttpObject} 类型输出，包括 {@link HttpRequest}、{@link HttpResponse}、
 * {@link HttpContent} 和 {@link LastHttpContent}，从而让应用层保持协议无关。
 * @see Http3FrameDecoder
 * @see Http3Frame
 */
public class Http3FrameToHttpDecoder implements ProtoHandler<Http3Frame, HttpObject> {
    private static final Logger logger = Logger.getLogger(Http3FrameToHttpDecoder.class);
    private final boolean       serverMode;
    private final Http3Settings localSettings;

    /**
     * 使用默认 QPACK settings 创建一个新的 HTTP/3 语义解码器。
     * @param serverMode 为 {@code true} 表示服务端模式，期望接收请求；否则为客户端模式，期望接收响应
     */
    public Http3FrameToHttpDecoder(boolean serverMode) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode));
    }

    /**
     * 使用自定义 QPACK settings 创建一个新的 HTTP/3 语义解码器。
     * @param serverMode 为 {@code true} 表示服务端模式，期望接收请求；否则为客户端模式，期望接收响应
     * @param maxTableSize QPACK 动态表最大容量，单位为字节
     * @param maxHeaderListSize 已解码头字段允许的最大总大小
     */
    public Http3FrameToHttpDecoder(boolean serverMode, int maxTableSize, int maxHeaderListSize) {
        this(serverMode, Http3Settings.defaultLocalSettings(serverMode, maxTableSize, maxHeaderListSize, Http3Settings.DEFAULT_LOCAL_QPACK_BLOCKED_STREAMS));
    }

    /**
     * 使用统一的 HTTP/3 settings 创建一个新的 HTTP/3 语义解码器。
     * @param serverMode 为 {@code true} 表示服务端模式，期望接收请求；否则为客户端模式，期望接收响应
     * @param localSettings 当前端点用于初始化解码器/QPACK 的本地参数
     */
    public Http3FrameToHttpDecoder(boolean serverMode, Http3Settings localSettings) {
        this.serverMode = serverMode;
        this.localSettings = localSettings != null ? new Http3Settings(localSettings) : Http3Settings.defaultLocalSettings(serverMode);
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
        context.context(Http3DecoderContent.class, new Http3DecoderContent(this.localSettings));
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http3Frame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        Http3DecoderContent state = context.context(Http3DecoderContent.class);
        boolean isPrintLog = context.getConfig().isPrintLog();

        while (src.hasMore()) {
            Http3Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            long streamId = frame.streamId();
            boolean isUnidirectional = (streamId & 0x02) != 0;

            if (isUnidirectional) {
                processControlFrame(context, state, frame, isPrintLog);
            } else {
                processRequestFrame(context, state, dst, frame, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * 处理双向请求或响应 stream 上的 frame。
     * 当前实现只识别 HEADERS 和 DATA。
     */
    private void processRequestFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Frame frame, boolean isPrintLog) {
        long streamId = frame.streamId();
        Http3Stream stream = state.getOrCreateStream(streamId);
        stream.state(Http3StreamState.OPEN);

        if (frame.type() == Http3FrameType.HEADERS) {
            processHeadersFrame(context, state, dst, stream, frame, isPrintLog);
        } else if (frame.type() == Http3FrameType.DATA) {
            processDataFrame(context, state, dst, stream, frame, isPrintLog);
        }
        // PUSH_PROMISE 以及保留或 grease frame 会被静默忽略。
    }

    /**
     * 处理 HEADERS frame，并输出 HttpRequest、HttpResponse 或 trailers。
     */
    private void processHeadersFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, Http3Frame frame, boolean isPrintLog) {
        HttpHeaders headers = state.decodeHeaders(frame.payload(), frame.payloadOffset(), frame.payloadLength());

        if (!stream.headersReceived()) {
            // 初始头，创建请求或响应对象。
            stream.markHeadersReceived();

            if (serverMode) {
                emitHttpRequest(context, state, dst, stream, headers, isPrintLog);
            } else {
                emitHttpResponse(context, dst, stream, headers, isPrintLog);
            }
        } else {
            // trailers。
            emitTrailers(context, state, dst, stream, headers, isPrintLog);
        }

        // 如果初始 HEADERS 同时带 FIN，说明没有消息体，需要补一个空的 LastHttpContent。
        if (frame.fin() && !stream.trailersReceived() && stream.state() == Http3StreamState.OPEN) {
            dst.offerMessage(new DefaultLastHttpContent(context.byteBufAllocator().buffer(0)));
            stream.state(Http3StreamState.HALF_CLOSED);
            state.closeStream(stream.streamId());

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " END stream=" + stream.streamId() + " (headers-only)");
            }
        }
    }

    /**
     * 在服务端模式下，根据已解码的 HEADERS 输出 HttpRequest 与普通头字段对象。
     */
    private void emitHttpRequest(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        int httpStreamId = Math.toIntExact(stream.streamId());
        String method = headers.getString(":method");
        String path = headers.getString(":path");
        String authority = headers.getString(":authority");
        String scheme = headers.getString(":scheme");

        if (StringUtils.isBlank(method)) {
            method = "GET";
        }
        if (StringUtils.isBlank(path)) {
            path = "/";
        }

        HttpMethod httpMethod = HttpMethod.valueOf(method);
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_3_0, httpMethod, path);
        request.streamId(httpStreamId);
        DefaultLastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();

        // 复制非伪头字段。
        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    regularHeaders.addHeader(name, value);
                }
            }
        }

        // 映射伪头字段。
        if (StringUtils.isNotBlank(authority)) {
            regularHeaders.addHeader(HttpHeaderNames.HOST, authority);
        }
        if (StringUtils.isNotBlank(scheme)) {
            regularHeaders.addHeader(HttpHeaderNames.X_FORWARDED_PROTO, scheme);
        }

        context.context(HttpVersion.class, request.protocolVersion());
        context.context(HttpScope.class, HttpScope.STREAM);
        dst.offerMessage(request);
        regularHeaders.streamId(httpStreamId);
        dst.offerMessage(regularHeaders);
        state.offerResponseStreamId(stream.streamId());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " REQUEST stream=" + stream.streamId() + " " + method + " " + path);
        }
    }

    /**
     * 在客户端模式下，根据已解码的 HEADERS 输出 HttpResponse 与普通头字段对象。
     */
    private void emitHttpResponse(ProtoContext context, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        int httpStreamId = Math.toIntExact(stream.streamId());
        String statusStr = headers.getString(":status");
        int statusCode = 200;
        if (StringUtils.isNotBlank(statusStr)) {
            statusCode = Integer.parseInt(statusStr);
        }

        HttpStatus status = HttpStatus.valueOf(statusCode);
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_3_0, status);
        response.streamId(httpStreamId);
        DefaultLastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();

        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    regularHeaders.addHeader(name, value);
                }
            }
        }

        context.context(HttpVersion.class, response.protocolVersion());
        context.context(HttpScope.class, HttpScope.STREAM);
        dst.offerMessage(response);
        regularHeaders.streamId(httpStreamId);
        dst.offerMessage(regularHeaders);

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " RESPONSE stream=" + stream.streamId() + " status=" + statusCode);
        }
    }

    /**
     * 以 LastHttpContent 的形式输出 trailers。
     */
    private void emitTrailers(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        int httpStreamId = Math.toIntExact(stream.streamId());
        stream.markTrailersReceived();
        DefaultTrailerHttpHeaders trailers = new DefaultTrailerHttpHeaders();

        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    trailers.addHeader(name, value);
                }
            }
        }

        trailers.streamId(httpStreamId);
        dst.offerMessage(trailers);
        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(context.byteBufAllocator().buffer(0));
        lastContent.streamId(httpStreamId);
        dst.offerMessage(lastContent);
        stream.state(Http3StreamState.HALF_CLOSED);
        state.closeStream(stream.streamId());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " TRAILERS stream=" + stream.streamId());
        }
    }

    /**
     * 处理 DATA frame，并输出 HttpContent 或 LastHttpContent。
     */
    private void processDataFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, Http3Frame frame, boolean isPrintLog) {
        int httpStreamId = Math.toIntExact(stream.streamId());
        int length = frame.payloadLength();
        ByteBuf content = context.byteBufAllocator().buffer(Math.max(length, 1));
        if (length > 0) {
            content.writeBytes(frame.payload(), frame.payloadOffset(), length);
        }
        content.markWriter();

        if (frame.fin()) {
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(content);
            lastContent.streamId(httpStreamId);
            dst.offerMessage(lastContent);
            stream.state(Http3StreamState.HALF_CLOSED);
            state.closeStream(stream.streamId());

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " LAST-DATA stream=" + stream.streamId() + " len=" + length);
            }
        } else {
            DefaultHttpContent httpContent = new DefaultHttpContent(content);
            httpContent.streamId(httpStreamId);
            dst.offerMessage(httpContent);

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " DATA stream=" + stream.streamId() + " len=" + length);
            }
        }
    }

    /**
     * 处理当前实现按单向 stream 接收的控制 frame。
     * 当前只识别 SETTINGS 和 GOAWAY。
     */
    private void processControlFrame(ProtoContext context, Http3DecoderContent state, Http3Frame frame, boolean isPrintLog) {
        if (frame.type() == Http3FrameType.SETTINGS) {
            processSettingsFrame(context, state, frame, isPrintLog);
        } else if (frame.type() == Http3FrameType.GOAWAY) {
            long[] goawayId = QuicVarInt.decode(frame.payload(), frame.payloadOffset());
            fireEvent(context, HttpConnectionGoAwayEvent.class, new HttpConnectionGoAwayEvent(goawayId[0], 0L, new byte[0]));
            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " GOAWAY lastAcceptedId=" + goawayId[0]);
            }
        }
    }

    /**
     * 处理 SETTINGS frame 负载，并应用远端 settings。
     */
    private void processSettingsFrame(ProtoContext context, Http3DecoderContent state, Http3Frame frame, boolean isPrintLog) {
        byte[] data = frame.payload();
        int pos = frame.payloadOffset();
        int end = pos + frame.payloadLength();

        while (pos < end) {
            long[] idResult = QuicVarInt.decode(data, pos);
            long settingId = idResult[0];
            pos += (int) idResult[1];

            long[] valResult = QuicVarInt.decode(data, pos);
            long settingValue = valResult[0];
            pos += (int) valResult[1];

            state.applyRemoteSetting(settingId, settingValue);
        }

        state.markSettingsReceived();

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " SETTINGS received");
        }
    }

    /**
     * 如果当前为服务端模式，则返回 {@code true}。
     */
    boolean isServerMode() {
        return this.serverMode;
    }

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing HTTP/3 event: " + eventType.getSimpleName(), e);
        }
    }

    @Override
    /**
     * 在连接关闭时释放解码状态中的资源。
     */
    public void onClose(ProtoContext context) {
        Http3DecoderContent state = context.context(Http3DecoderContent.class);
        if (state != null) {
            state.releaseAll();
        }
    }
}
