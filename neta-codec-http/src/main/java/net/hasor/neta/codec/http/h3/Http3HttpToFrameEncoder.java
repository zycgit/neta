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
 * HTTP/3 semantic encoder that converts standard {@link HttpObject} instances
 * into {@link Http3Frame} objects with QPACK-compressed headers.
 * <p>
 * This encoder handles pseudo-header mapping, QPACK header compression,
 * stream ID allocation, and FIN flag management. The resulting {@link Http3Frame}
 * objects are then serialized to wire format by {@link Http3FrameEncoder}.
 * <p>
 * <b>Encode path:</b> {@code HttpObject → Http3Frame → ByteBuf}
 * @see Http3FrameEncoder
 * @see Http3Frame
 */
public class Http3HttpToFrameEncoder implements ProtoHandler<HttpObject, Http3Frame> {
    private static final Logger     logger = Logger.getLogger(Http3HttpToFrameEncoder.class);
    private final        HttpScheme scheme = HttpScheme.HTTPS;
    private final        boolean    serverMode;
    private final        int        maxTableSize;

    /**
     * Creates a new HTTP/3 semantic encoder with default QPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http3HttpToFrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * Creates a new HTTP/3 semantic encoder with custom QPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxTableSize maximum QPACK dynamic table size in bytes
     */
    public Http3HttpToFrameEncoder(boolean serverMode, int maxTableSize) {
        this.serverMode = serverMode;
        this.maxTableSize = maxTableSize;
    }

    @Override
    public void onInit(ProtoContext context) throws Throwable {
        context.context(Http3EncoderContent.class, new Http3EncoderContent(serverMode, maxTableSize));
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http3Frame> dst) throws Throwable {
        Http3EncoderContent state = context.context(Http3EncoderContent.class);
        boolean isPrintLog = context.getConfig() != null && context.getConfig().isPrintLog();

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
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(context, state, (LastHttpContent) msg, dst, isPrintLog);
            } else if (msg instanceof HttpContent) {
                encodeContent(context, state, (HttpContent) msg, dst, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /** Encodes a complete HTTP request (headers + body) as HEADERS + DATA frames. */
    private void encodeFullRequest(ProtoContext context, Http3EncoderContent state, FullHttpRequest request, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        long streamId = state.allocateNextStreamId();

        // QPACK encode headers
        state.beginHeaderEncode();
        state.encodeHeader(":method", request.method().name());
        state.encodeHeader(":path", request.uri());
        state.encodeHeader(":scheme", scheme.name());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            state.encodeHeader(":authority", host);
        }
        encodeNonPseudoHeaders(state, request.headers());
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

    /** Encodes a complete HTTP response (headers + body). */
    private void encodeFullResponse(ProtoContext context, Http3EncoderContent state, FullHttpResponse response, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        state.consumeResponseStreamId();
        long streamId = state.currentStreamId();

        state.beginHeaderEncode();
        state.encodeHeader(":status", String.valueOf(response.status().code()));
        encodeNonPseudoHeaders(state, response.headers());
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

    /** Encodes an HTTP request (headers only). */
    private void encodeRequest(ProtoContext context, Http3EncoderContent state, HttpRequest request, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        long streamId = state.allocateNextStreamId();

        state.beginHeaderEncode();
        state.encodeHeader(":method", request.method().name());
        state.encodeHeader(":path", request.uri());
        state.encodeHeader(":scheme", scheme.name());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            state.encodeHeader(":authority", host);
        }
        encodeNonPseudoHeaders(state, request.headers());

        int headerBlockLen = state.headerEncodedLength();
        dst.offerMessage(Http3Frame.headers(streamId, false, state.headerEncodedBuffer(), 0, headerBlockLen));

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " REQUEST stream=" + streamId + " " + request.method() + " " + request.uri());
        }
    }

    /** Encodes an HTTP response (headers only). */
    private void encodeResponse(ProtoContext context, Http3EncoderContent state, HttpResponse response, ProtoSndQueue<Http3Frame> dst, boolean isPrintLog) {
        state.consumeResponseStreamId();
        long streamId = state.currentStreamId();

        state.beginHeaderEncode();
        state.encodeHeader(":status", String.valueOf(response.status().code()));
        encodeNonPseudoHeaders(state, response.headers());

        int headerBlockLen = state.headerEncodedLength();
        dst.offerMessage(Http3Frame.headers(streamId, false, state.headerEncodedBuffer(), 0, headerBlockLen));

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-SND] ch=" + channelID + " RESPONSE stream=" + streamId + " status=" + response.status().code());
        }
    }

    /** Encodes body content as a DATA frame. */
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

    /** Encodes last content as a DATA frame with FIN. */
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

    /** Encodes non-pseudo headers into the QPACK encoder. */
    private void encodeNonPseudoHeaders(Http3EncoderContent state, HttpHeaders src) {
        for (java.util.Map.Entry<String, String> entry : src) {
            String name = entry.getKey();
            if (!StringUtils.startsWith(name, ":") && !StringUtils.equalsIgnoreCase(name, HttpHeaderNames.HOST)) {
                state.encodeHeader(name.toLowerCase(), entry.getValue());
            }
        }
    }
}
