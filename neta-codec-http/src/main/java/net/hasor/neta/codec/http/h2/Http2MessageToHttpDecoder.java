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

/** Converts semantic {@link Http2Message} objects into protocol-agnostic {@link HttpObject} values. */
public class Http2MessageToHttpDecoder implements ProtoHandler<Http2Message, HttpObject> {
    private static final Logger logger = Logger.getLogger(Http2MessageToHttpDecoder.class);

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Message> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        while (src.hasMore()) {
            Http2Message message = src.takeMessage();
            if (message == null) {
                continue;
            }
            if (message instanceof Http2HeadersMessage) {
                emitHttpMessage(state, context, dst, (Http2HeadersMessage) message);
            } else if (message instanceof Http2DataMessage) {
                emitDataMessage(state, context, dst, (Http2DataMessage) message);
            }
        }
        return ProtoStatus.Next;
    }

    private void emitDataMessage(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2DataMessage message) {
        int streamId = message.streamId();
        if (message.endStream()) {
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(message.content());
            lastContent.streamId(streamId);
            dst.offerMessage(lastContent);
            state.offerResponseStreamId(streamId);
            Http2Stream stream = state.getStream(streamId);
            if (stream != null) {
                stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
            }
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " DATA stream=" + streamId + " dataLen=" + message.content().readableBytes() + " END_STREAM streamState=HALF_CLOSED_REMOTE");
            }
        } else {
            DefaultHttpContent chunk = new DefaultHttpContent(message.content());
            chunk.streamId(streamId);
            dst.offerMessage(chunk);
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " DATA stream=" + streamId + " dataLen=" + message.content().readableBytes());
            }
        }
    }

    private void emitHttpMessage(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2HeadersMessage message) {
        int streamId = message.streamId();
        DefaultHttpHeaders headers = message.headers();
        DefaultLastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();
        state.setLastEmittedStreamId(streamId);
        if (message.endStream()) {
            state.offerResponseStreamId(streamId);
        }
        String status = headers.getString(":status");
        if (status != null) {
            int statusCode = Integer.parseInt(status);
            copyRegularHeaders(headers, regularHeaders);
            HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, httpStatus);
            response.streamId(streamId);
            dst.offerMessage(response);
            dst.offerMessage(regularHeaders);
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " HEADERS stream=" + streamId + " RESPONSE status=" + statusCode + " endStream=" + message.endStream());
            }
        } else {
            String method = headers.getString(":method");
            String path = headers.getString(":path");
            String authority = headers.getString(":authority");
            String scheme = headers.getString(":scheme");
            if (StringUtils.isBlank(method) || StringUtils.isBlank(path)) {
                throw new HttpProtocolViolationException("HTTP/2: missing required pseudo-header :method or :path");
            }
            copyRegularHeaders(headers, regularHeaders);
            if (StringUtils.isNotBlank(authority) && StringUtils.isBlank(regularHeaders.getString(HttpHeaderNames.HOST))) {
                regularHeaders.addHeader(HttpHeaderNames.HOST, authority);
            }
            if (StringUtils.isNotBlank(scheme)) {
                regularHeaders.addHeader(HttpHeaderNames.X_FORWARDED_PROTO, scheme);
            }
            HttpMethod httpMethod = HttpMethod.valueOf(method);
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, httpMethod, path);
            request.streamId(streamId);
            dst.offerMessage(request);
            dst.offerMessage(regularHeaders);
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " HEADERS stream=" + streamId + " REQUEST " + method + " " + path + " endStream=" + message.endStream());
            }
        }
        if (message.endStream()) {
            DefaultLastHttpContent emptyLast = new DefaultLastHttpContent(ByteBuf.EMPTY);
            emptyLast.streamId(streamId);
            dst.offerMessage(emptyLast);
            Http2Stream stream = state.getStream(streamId);
            if (stream != null) {
                stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
            }
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " LastHttpContent stream=" + streamId + " streamState=HALF_CLOSED_REMOTE");
            }
        }
    }

    private void copyRegularHeaders(DefaultHttpHeaders headers, DefaultLastHttpHeaders regularHeaders) {
        for (String name : headers.headerNames()) {
            if (StringUtils.startsWith(name, ":")) {
                continue;
            }
            for (String value : headers.getValues(name)) {
                regularHeaders.addHeader(name, value);
            }
        }
    }
}