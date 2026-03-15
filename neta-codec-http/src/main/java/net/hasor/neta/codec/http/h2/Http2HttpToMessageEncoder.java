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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/** Converts {@link HttpObject} instances into semantic {@link Http2Message} objects. */
public class Http2HttpToMessageEncoder implements ProtoHandler<HttpObject, Http2Message> {
    private static final Logger     logger = Logger.getLogger(Http2HttpToMessageEncoder.class);
    private final        HttpScheme scheme = HttpScheme.HTTPS;
    private final        boolean    serverMode;
    private final        int        maxHeaderTableSize;

    public Http2HttpToMessageEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    public Http2HttpToMessageEncoder(boolean serverMode, int maxHeaderTableSize) {
        this.serverMode = serverMode;
        this.maxHeaderTableSize = maxHeaderTableSize;
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        if (context.context(Http2EncoderContent.class) == null) {
            context.context(Http2EncoderContent.class, new Http2EncoderContent(this.serverMode, this.maxHeaderTableSize));
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http2Message> dst) throws Throwable {
        Http2EncoderContent state = context.context(Http2EncoderContent.class);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            if (msg instanceof FullHttpResponse) {
                encodeFullResponse(state, context, (FullHttpResponse) msg, dst);
            } else if (msg instanceof FullHttpRequest) {
                encodeFullRequest(state, context, (FullHttpRequest) msg, dst);
            } else if (msg instanceof HttpResponse) {
                encodeResponseHeaders(state, context, (HttpResponse) msg, dst);
            } else if (msg instanceof HttpRequest) {
                encodeRequestHeaders(state, context, (HttpRequest) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(state, context, (LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                encodeContent(state, context, (HttpContent) msg, dst);
            }
        }
        return ProtoStatus.Next;
    }

    private void encodeFullResponse(Http2EncoderContent state, ProtoContext context, FullHttpResponse response, ProtoSndQueue<Http2Message> dst) {
        if (!(response instanceof DefaultFullHttpResponse)) {
            throw new IllegalArgumentException("FullHttpResponse must be DefaultFullHttpResponse");
        }
        DefaultFullHttpResponse fullResponse = (DefaultFullHttpResponse) response;
        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " FULL_RESPONSE stream=" + state.currentStreamId() + " status=" + response.status().code() + " bodyLen=" + (hasBody ? body.readableBytes() : 0));
        }
        dst.offerMessage(new Http2HeadersMessage(state.currentStreamId(), buildResponseHeaders(fullResponse, fullResponse), !hasBody));
        if (hasBody) {
            dst.offerMessage(new Http2DataMessage(state.currentStreamId(), body, true));
        }
    }

    private void encodeFullRequest(Http2EncoderContent state, ProtoContext context, FullHttpRequest request, ProtoSndQueue<Http2Message> dst) {
        if (!(request instanceof DefaultFullHttpRequest)) {
            throw new IllegalArgumentException("FullHttpRequest must be DefaultFullHttpRequest");
        }
        state.allocateNextStreamId();
        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " FULL_REQUEST stream=" + state.currentStreamId() + " " + request.method().name() + " " + request.uri() + " bodyLen=" + (hasBody ? body.readableBytes() : 0));
        }
        dst.offerMessage(new Http2HeadersMessage(state.currentStreamId(), buildRequestHeaders(request, request), !hasBody));
        if (hasBody) {
            dst.offerMessage(new Http2DataMessage(state.currentStreamId(), body, true));
        }
    }

    private void encodeResponseHeaders(Http2EncoderContent state, ProtoContext context, HttpResponse response, ProtoSndQueue<Http2Message> dst) {
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " HEADERS stream=" + state.currentStreamId() + " RESPONSE status=" + response.status().code());
        }
        dst.offerMessage(new Http2HeadersMessage(state.currentStreamId(), buildResponseHeaders(response, null), false));
    }

    private void encodeRequestHeaders(Http2EncoderContent state, ProtoContext context, HttpRequest request, ProtoSndQueue<Http2Message> dst) {
        state.allocateNextStreamId();
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " HEADERS stream=" + state.currentStreamId() + " REQUEST " + request.method().name() + " " + request.uri());
        }
        dst.offerMessage(new Http2HeadersMessage(state.currentStreamId(), buildRequestHeaders(request, null), false));
    }

    private void encodeContent(Http2EncoderContent state, ProtoContext context, HttpContent content, ProtoSndQueue<Http2Message> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " DATA stream=" + state.currentStreamId() + " dataLen=" + body.readableBytes());
        }
        dst.offerMessage(new Http2DataMessage(state.currentStreamId(), body, false));
    }

    private void encodeLastContent(Http2EncoderContent state, ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<Http2Message> dst) {
        ByteBuf body = lastContent.content();
        int bodyLen = body != null ? body.readableBytes() : 0;
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-SND] ch=" + channelID + " DATA stream=" + state.currentStreamId() + " dataLen=" + bodyLen + " flags=END_STREAM");
        }
        dst.offerMessage(new Http2DataMessage(state.currentStreamId(), body != null ? body : context.byteBufAllocator().buffer(0), true));
    }

    private DefaultHttpHeaders buildResponseHeaders(HttpResponse response, HttpHeaders regularHeaders) {
        DefaultHttpHeaders target = new DefaultHttpHeaders();
        target.addHeader(":status", String.valueOf(response.status().code()));
        copyRegularHeaders(regularHeaders, target);
        return target;
    }

    private DefaultHttpHeaders buildRequestHeaders(HttpRequest request, HttpHeaders regularHeaders) {
        DefaultHttpHeaders target = new DefaultHttpHeaders();
        target.addHeader(":method", request.method().name());
        target.addHeader(":path", request.uri());
        String host = null;
        if (regularHeaders != null) {
            host = regularHeaders.getString(HttpHeaderNames.HOST);
        }
        if (StringUtils.isNotBlank(host)) {
            target.addHeader(":authority", host);
        }
        target.addHeader(":scheme", this.scheme.name());
        copyRegularHeaders(regularHeaders, target);
        return target;
    }

    private void copyRegularHeaders(HttpHeaders source, DefaultHttpHeaders target) {
        if (source == null || source.headerSize() == 0) {
            return;
        }
        for (String headerName : source.headerNames()) {
            String name = headerName.toLowerCase();
            if (StringUtils.equals(HttpHeaderNames.CONNECTION, name) || StringUtils.equals(HttpHeaderNames.TRANSFER_ENCODING, name) || StringUtils.equals("keep-alive", name) || StringUtils.equals("proxy-connection", name) || StringUtils.equals(HttpHeaderNames.UPGRADE, name) || StringUtils.equals(HttpHeaderNames.HOST, name)) {
                continue;
            }
            for (String value : source.getValues(headerName)) {
                target.addHeader(name, value);
            }
        }
    }
}