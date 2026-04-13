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
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/**
 * Encodes staged {@link HttpObject} instances and HTTP/2 events into wire-level {@link Http2Frame} objects.
 * <p>
 * This encoder implements the send-side message-layer state machine of HTTP/2. It typically sits in
 * front of {@link Http2FrameEncoder}, receiving upstream request objects, response objects, content
 * objects, and HTTP/2 control events, then converting them into HEADERS, DATA, SETTINGS, PING,
 * GOAWAY, RST_STREAM, PUSH_PROMISE, and related frames.
 * <p>
 * A single request or response usually appears at the encoder input as an ordered object flow:
 * <pre>
 *   [HttpRequest/HttpResponse] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [LastHttpContent]
 * </pre>
 * Start lines and header fields are rewritten into HTTP/2 header blocks, content objects are sliced
 * into DATA frames, and control events are converted into the corresponding control frames through
 * {@link #onEvent(ProtoContext, SoEvent)}.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("h2-object", new Http2ObjectEncoder(false));
 *   ctx.addLastEncoder("h2-frame", new Http2FrameEncoder());
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   HttpObject + Http2 events
 *      -> Http2ObjectEncoder
 *      -> Http2Frame
 *      -> Http2FrameEncoder
 *      -> socket bytes
 * </pre>
 * <p>
 * This layer also handles connection preface and settings initiation, stream-ID allocation, HPACK
 * header-block encoding, DATA slicing, half-close tracking, and mapping protocol errors to control
 * frames.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
class Http2ObjectEncoder implements ProtoHandler<HttpObject, Http2Frame> {
    private static final Logger        logger                 = Logger.getLogger(Http2ObjectEncoder.class);
    private static final byte[]        CLIENT_PREFACE         = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int           DEFAULT_MAX_FRAME_SIZE = 16384;
    private final        boolean       serverMode;
    private final        Http2Settings localSettings;
    private final        HttpScheme    scheme                 = HttpScheme.HTTPS;

    public Http2ObjectEncoder(boolean serverMode) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode));
    }

    public Http2ObjectEncoder(boolean serverMode, Http2Settings localSettings) {
        this.serverMode = serverMode;
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : Http2Settings.defaultLocalSettings(serverMode);
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        Http2EncoderContent state = context.rootContext(Http2EncoderContent.class);
        if (state == null) {
            state = new Http2EncoderContent(this.serverMode, this.localSettings);
            Http2EncoderContent shared = context.rootContext(Http2EncoderContent.class, state);
            if (shared != null) {
                state = shared;
            }
        }

        if (context.context(Http2EncoderContent.class) == null) {
            context.context(Http2EncoderContent.class, state);
        }
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) throws Throwable {
        try {
            Object eventData = event.getData();
            if (eventData instanceof Http2PingEvent) {
                this.sendPing(context, (Http2PingEvent) eventData);
                return false;
            }

            if (eventData instanceof Http2GoawayEvent) {
                this.sendGoaway(context, (Http2GoawayEvent) eventData);
                return false;
            }

            if (eventData instanceof Http2ResetEvent) {
                this.sendResetStream(context, (Http2ResetEvent) eventData);
                return false;
            }

            if (eventData instanceof Http2PriorityEvent) {
                this.sendPriority(context, (Http2PriorityEvent) eventData);
                return false;
            }

            if (eventData instanceof Http2PushPromiseEvent) {
                this.sendPushPromise(context, (Http2PushPromiseEvent) eventData);
                return false;
            }

            return true;
        } catch (HttpProtocolStreamException | HttpProtocolConnectionException e) {
            this.handleProtocolError(context, e);
            return false;
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        this.offerConnectionPrefaceIfNeeded(context, dst);
        this.flushPendingControlFrames(context, dst);
        Http2EncoderContent state = context.context(Http2EncoderContent.class);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            this.encodeHttpObject(state, context, msg, dst);
        }
        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
        if (!(e instanceof HttpProtocolStreamException) && !(e instanceof HttpProtocolConnectionException)) {
            return ProtoStatus.Next;
        }

        if (this.handleProtocolError(context, (HttpProtocolException) e)) {
            eh.clear();
            return ProtoStatus.Next;
        }

        eh.clear();
        return ProtoStatus.Stop;
    }

    private boolean handleProtocolError(ProtoContext context, HttpProtocolException protocolError) {
        if (protocolError instanceof HttpProtocolStreamException && protocolError.getStreamId() > 0) {
            this.handleStreamError(context, protocolError);
            return true;
        }

        this.handleConnectionError(context, protocolError);
        context.getChannel().close();
        return false;
    }

    private boolean offerConnectionPrefaceIfNeeded(ProtoContext context, ProtoSndQueue<Http2Frame> dst) {
        Http2EncoderContent state = context.context(Http2EncoderContent.class);
        if (state == null || state.isPrefaceSent()) {
            return false;
        }
        // Mark first to avoid re-entrant duplicate preface emission on synchronous transports.
        state.markPrefaceSent();

        if (this.serverMode) {
            int initialWindowSize = this.localSettings.initialWindowSize();
            dst.offerMessage(serverSettingsFrame(this.localSettings));
            if (initialWindowSize > 65535) {
                dst.offerMessage(buildWindowUpdateFrame(0, initialWindowSize - 65535));
            }
        } else {
            dst.offerMessage(new Http2Frame(Http2FrameType.PREFACE, 0, 0, CLIENT_PREFACE));
            dst.offerMessage(settingsFrame(false, toSettingsMap(this.localSettings)));
        }

        return true;
    }

    private boolean flushPendingControlFrames(ProtoContext context, ProtoSndQueue<Http2Frame> dst) {
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        if (decoderState == null) {
            return false;
        }

        boolean hasAny = false;
        if (decoderState != null) {
            if (decoderState.consumeSettingsAck()) {
                dst.offerMessage(settingsFrame(true, null));
                hasAny = true;
            }

            byte[] pingPayload;
            while ((pingPayload = decoderState.pollPendingPingAck()) != null) {
                dst.offerMessage(pingFrame(true, pingPayload));
                hasAny = true;
            }

            Http2DecoderContent.PendingWindowUpdate windowUpdate;
            while ((windowUpdate = decoderState.pollPendingWindowUpdate()) != null) {
                Http2Frame frame = buildWindowUpdateFrame(windowUpdate.streamId(), windowUpdate.increment());
                dst.offerMessage(frame);
                hasAny = true;
            }
        }
        return hasAny;
    }

    ProtoStatus flushPendingFrames(ProtoContext context, ProtoSndQueue<Http2Frame> dst) {
        boolean hasAny = this.offerConnectionPrefaceIfNeeded(context, dst);
        if (this.flushPendingControlFrames(context, dst)) {
            hasAny = true;
        }
        return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
    }

    private void encodeHttpObject(Http2EncoderContent state, ProtoContext context, HttpObject msg, ProtoSndQueue<Http2Frame> dst) {
        if (msg instanceof FullHttpResponse) {
            this.encodeFullResponse(state, context, (FullHttpResponse) msg, dst);
            return;
        }
        if (msg instanceof FullHttpRequest) {
            this.encodeFullRequest(state, context, (FullHttpRequest) msg, dst);
            return;
        }

        if (msg instanceof HttpResponse) {
            this.bindResponse(state, context, (HttpResponse) msg);
            return;
        }
        if (msg instanceof HttpRequest) {
            this.bindRequest(state, (HttpRequest) msg);
            return;
        }
        if (msg instanceof TrailerHttpHeaders) {
            this.encodeTrailerHeaders(state, context, (TrailerHttpHeaders) msg, dst);
            return;
        }
        if (msg instanceof HttpHeaders) {
            this.encodeHeaderBlock(state, context, (HttpHeaders) msg, dst);
            return;
        }
        if (msg instanceof LastHttpContent) {
            this.encodeLastContent(state, context, (LastHttpContent) msg, dst);
            return;
        }
        if (msg instanceof HttpByteBuf) {
            this.encodeByteBuf(state, context, (HttpByteBuf) msg, dst);
            return;
        }
        if (msg instanceof HttpContent) {
            this.encodeContent(state, context, (HttpContent) msg, dst);
        }
    }

    private void bindRequest(Http2EncoderContent state, HttpRequest request) {
        long streamId = request.streamId();
        if (streamId > 0) {
            state.setCurrentStreamId(streamId);
        } else if (!this.serverMode) {
            request.streamId(state.allocateNextStreamId());
        }

        state.pendingStartLine(request);
        state.trailingHeadersSent(false);
    }

    private void bindResponse(Http2EncoderContent state, ProtoContext context, HttpResponse response) {
        long streamId = this.resolveResponseStreamId(state, context, response);
        response.streamId(streamId);
        state.pendingStartLine(response);
        state.trailingHeadersSent(false);
    }

    private void encodeFullResponse(Http2EncoderContent state, ProtoContext context, FullHttpResponse response, ProtoSndQueue<Http2Frame> dst) {
        long streamId = this.resolveResponseStreamId(state, context, response);
        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;
        boolean webSocketUpgrade = isWebSocketUpgradeHandshake(response);
        this.encodeHeaders(state, this.buildResponseHeaders(response, response), streamId, !hasBody && !webSocketUpgrade, dst);
        if (hasBody) {
            this.encodeData(context, streamId, body, true, dst);
        } else if (!webSocketUpgrade) {
            this.recordOutboundHalfClosed(context, streamId);
        }
        state.clearPendingStartLine();
        state.trailingHeadersSent(false);
    }

    private void encodeFullRequest(Http2EncoderContent state, ProtoContext context, FullHttpRequest request, ProtoSndQueue<Http2Frame> dst) {
        long streamId = request.streamId() > 0 ? request.streamId() : state.allocateNextStreamId();
        request.streamId(streamId);
        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;
        boolean webSocketUpgrade = isWebSocketUpgradeHandshake(request) || isStandardWebSocketConnect(request, request);
        this.encodeHeaders(state, this.buildRequestHeaders(request, request), streamId, !hasBody && !webSocketUpgrade, dst);
        if (hasBody) {
            this.encodeData(context, streamId, body, true, dst);
        } else if (!webSocketUpgrade) {
            this.recordOutboundHalfClosed(context, streamId);
        }
        state.clearPendingStartLine();
        state.trailingHeadersSent(false);
    }

    private void encodeHeaderBlock(Http2EncoderContent state, ProtoContext context, HttpHeaders headers, ProtoSndQueue<Http2Frame> dst) {
        if (state.pendingStartLine() != null) {
            long streamId = this.resolveActiveStreamId(state, headers);
            this.encodePendingStartLine(state, headers, streamId, false, dst);
            return;
        }

        String msg = "HTTP/2: missing request/response start-line before header block";
        throw new HttpProtocolConnectionException(Http2ErrorCode.PROTOCOL_ERROR, msg);
    }

    private void encodeTrailerHeaders(Http2EncoderContent state, ProtoContext context, TrailerHttpHeaders headers, ProtoSndQueue<Http2Frame> dst) {
        long streamId = this.resolveActiveStreamId(state, headers);
        this.encodeHeaders(state, this.buildTrailerHeaders(headers), streamId, true, dst);
        this.recordOutboundHalfClosed(context, streamId);
        state.trailingHeadersSent(true);
        state.clearPendingStartLine();
    }

    private void encodeContent(Http2EncoderContent state, ProtoContext context, HttpContent content, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        long streamId = this.ensureHeaderBlock(state, context, content, dst);
        this.encodeData(context, streamId, body, false, dst);
    }

    private void encodeByteBuf(Http2EncoderContent state, ProtoContext context, HttpByteBuf content, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        long streamId = this.ensureHeaderBlock(state, context, content, dst);
        this.encodeData(context, streamId, body, false, dst);
    }

    private void encodeLastContent(Http2EncoderContent state, ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<Http2Frame> dst) {
        long streamId = this.ensureHeaderBlock(state, context, lastContent, dst);
        ByteBuf body = lastContent.content();
        int bodyLen = body != null ? body.readableBytes() : 0;
        boolean pendingUpgradeStream = state.consumePendingUpgradeStream(streamId);
        if (state.trailingHeadersSent()) {
            if (bodyLen > 0) {
                throw new HttpProtocolStreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, "HTTP/2: trailing headers must be followed by an empty LastHttpContent");
            }
            state.trailingHeadersSent(false);
            state.clearPendingStartLine();
            return;
        }
        if (pendingUpgradeStream && bodyLen == 0) {
            state.clearPendingStartLine();
            state.trailingHeadersSent(false);
            return;
        }
        this.encodeData(context, streamId, body != null ? body : context.byteBufAllocator().buffer(0), true, dst);
        this.recordOutboundHalfClosed(context, streamId);
        state.clearPendingStartLine();
        state.trailingHeadersSent(false);
    }

    private void recordOutboundHalfClosed(ProtoContext context, long streamId) {
        this.fireEventRcv(context, Http2StreamCloseEvent.class, new Http2StreamCloseEvent(streamId, false).remote(false));
    }

    private long ensureHeaderBlock(Http2EncoderContent state, ProtoContext context, HttpObject content, ProtoSndQueue<Http2Frame> dst) {
        long streamId = this.resolveActiveStreamId(state, content);
        if (state.pendingStartLine() != null) {
            this.encodePendingStartLine(state, null, streamId, false, dst);
        }
        return streamId;
    }

    private void encodePendingStartLine(Http2EncoderContent state, HttpHeaders regularHeaders, long streamId, boolean endStream, ProtoSndQueue<Http2Frame> dst) {
        HttpObject pendingStartLine = state.pendingStartLine();
        if (pendingStartLine == null) {
            String msg = "HTTP/2: missing request/response start-line before header block";
            throw new HttpProtocolConnectionException(Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        if (isWebSocketUpgradeHandshake(regularHeaders) || (state.pendingStartLineIsRequest() && isStandardWebSocketConnect(state.pendingRequest(), regularHeaders))) {
            state.markPendingUpgradeStream(streamId);
        }

        HttpHeaders headers = state.pendingStartLineIsRequest() ? this.buildRequestHeaders(state.pendingRequest(), regularHeaders) : this.buildResponseHeaders(state.pendingResponse(), regularHeaders);
        this.encodeHeaders(state, headers, streamId, endStream, dst);
        state.clearPendingStartLine();
    }

    private long resolveResponseStreamId(Http2EncoderContent state, ProtoContext context, HttpObject responseObject) {
        long streamId = responseObject.streamId();
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        if (streamId > 0) {
            state.setCurrentStreamId(streamId);
            if (decoderState != null) {
                decoderState.removeFromResponseQueue(streamId);
            }
            return streamId;
        }
        if (decoderState != null) {
            long queuedStreamId = decoderState.pollResponseStreamId();
            streamId = queuedStreamId;
            if (streamId > 0) {
                state.setCurrentStreamId(queuedStreamId);
                responseObject.streamId(queuedStreamId);
                return queuedStreamId;
            }
        }

        long currentStreamId = state.currentStreamId();
        streamId = currentStreamId;
        if (streamId > 0) {
            responseObject.streamId(currentStreamId);
            return currentStreamId;
        }

        String msg = "HTTP/2: missing response streamId";
        throw new HttpProtocolConnectionException(Http2ErrorCode.PROTOCOL_ERROR, msg);
    }

    private long resolveActiveStreamId(Http2EncoderContent state, HttpObject httpObject) {
        long streamId = httpObject.streamId();
        if (streamId > 0) {
            state.setCurrentStreamId(streamId);
            return streamId;
        }

        long currentStreamId = state.currentStreamId();
        streamId = currentStreamId;
        if (streamId > 0) {
            httpObject.streamId(currentStreamId);
            return currentStreamId;
        }

        String msg = "HTTP/2: missing active streamId";
        throw new HttpProtocolConnectionException(Http2ErrorCode.PROTOCOL_ERROR, msg);
    }

    private HttpHeaders buildResponseHeaders(HttpResponse response, HttpHeaders regularHeaders) {
        HttpHeaders target = new DefaultHttpHeaders();
        target.addHeader(HttpHeaderNames.PSEUDO_STATUS, String.valueOf(response.status().code()));
        this.copyRegularHeaders(regularHeaders, target, shouldPreserveWebSocketUpgradeHeaders(regularHeaders));
        return target;
    }

    private HttpHeaders buildRequestHeaders(HttpRequest request, HttpHeaders regularHeaders) {
        HttpHeaders target = new DefaultHttpHeaders();
        target.addHeader(HttpHeaderNames.PSEUDO_METHOD, request.method().name());
        target.addHeader(HttpHeaderNames.PSEUDO_PATH, request.uri());
        String protocol = regularHeaders != null ? regularHeaders.getString(HttpHeaderNames.PSEUDO_PROTOCOL) : null;
        if (StringUtils.isNotBlank(protocol)) {
            target.addHeader(HttpHeaderNames.PSEUDO_PROTOCOL, protocol);
        }
        String host = regularHeaders != null ? regularHeaders.getString(HttpHeaderNames.HOST) : null;
        if (StringUtils.isNotBlank(host)) {
            target.addHeader(HttpHeaderNames.PSEUDO_AUTHORITY, host);
        }
        String schemeName = regularHeaders != null ? regularHeaders.getString(HttpHeaderNames.X_FORWARDED_PROTO) : null;
        target.addHeader(HttpHeaderNames.PSEUDO_SCHEME, StringUtils.isNotBlank(schemeName) ? schemeName : this.scheme.name());
        this.copyRegularHeaders(regularHeaders, target, shouldPreserveWebSocketUpgradeHeaders(regularHeaders));
        return target;
    }

    private HttpHeaders buildTrailerHeaders(HttpHeaders regularHeaders) {
        HttpHeaders target = new DefaultHttpHeaders();
        this.copyRegularHeaders(regularHeaders, target, false);
        return target;
    }

    private void copyRegularHeaders(HttpHeaders source, HttpHeaders target, boolean preserveWebSocketUpgradeHeaders) {
        if (source == null || source.headerSize() == 0) {
            return;
        }

        for (String headerName : source.headerNames()) {
            String name = headerName.toLowerCase();
            boolean preserveHeader = preserveWebSocketUpgradeHeaders && (StringUtils.equals(HttpHeaderNames.CONNECTION, name) || StringUtils.equals(HttpHeaderNames.UPGRADE, name));
            if (StringUtils.startsWith(name, ":") ||                        //
                    (!preserveHeader && StringUtils.equals(HttpHeaderNames.CONNECTION, name)) ||       //
                    StringUtils.equals(HttpHeaderNames.TRANSFER_ENCODING, name) ||//
                    StringUtils.equals(HttpHeaderNames.KEEP_ALIVE, name) ||       //
                    StringUtils.equals(HttpHeaderNames.PROXY_CONNECTION, name) || //
                    (!preserveHeader && StringUtils.equals(HttpHeaderNames.UPGRADE, name)) ||          //
                    StringUtils.equals(HttpHeaderNames.HOST, name)) {
                continue;
            }
            for (String value : source.getValues(headerName)) {
                target.addHeader(name, value);
            }
        }
    }

    private boolean shouldPreserveWebSocketUpgradeHeaders(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        String upgrade = headers.getString(HttpHeaderNames.UPGRADE);
        String connection = headers.getString(HttpHeaderNames.CONNECTION);
        return StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && StringUtils.containsIgnoreCase(connection, HttpHeaderValues.UPGRADE);
    }

    private boolean isWebSocketUpgradeHandshake(HttpHeaders headers) {
        return shouldPreserveWebSocketUpgradeHeaders(headers);
    }

    private boolean isStandardWebSocketConnect(HttpRequest request, HttpHeaders headers) {
        if (request == null || !HttpMethod.CONNECT.equals(request.method())) {
            return false;
        }
        return headers != null && StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, headers.getString(HttpHeaderNames.PSEUDO_PROTOCOL));
    }

    private void encodeHeaders(Http2EncoderContent state, HttpHeaders headers, long streamId, boolean endStream, ProtoSndQueue<Http2Frame> dst) {
        state.beginHeaderEncode();
        for (String headerName : headers.headerNames()) {
            for (String value : headers.getValues(headerName)) {
                state.encodeHeader(headerName.toLowerCase(), value);
            }
        }

        byte[] headerBlock = state.finishHeaderEncode();
        int maxFrameSize = Math.max(this.localSettings.maxFrameSize(), 1);
        int offset = 0;
        int firstChunkLength = Math.min(headerBlock.length, maxFrameSize);
        int firstFlags = endStream ? Http2Flags.END_STREAM : Http2Flags.NONE;
        if (firstChunkLength == headerBlock.length) {
            firstFlags |= Http2Flags.END_HEADERS;
        }
        dst.offerMessage(Http2Frame.headers(streamId, firstFlags, headerBlock, offset, firstChunkLength));

        offset += firstChunkLength;
        while (offset < headerBlock.length) {
            int chunkLength = Math.min(headerBlock.length - offset, maxFrameSize);
            int flags = (offset + chunkLength) == headerBlock.length ? Http2Flags.END_HEADERS : Http2Flags.NONE;
            dst.offerMessage(new Http2Frame(Http2FrameType.CONTINUATION, flags, streamId, headerBlock, offset, chunkLength));
            offset += chunkLength;
        }
    }

    private void encodeData(ProtoContext context, long streamId, ByteBuf body, boolean endStream, ProtoSndQueue<Http2Frame> dst) {
        int bodyLen = body != null ? body.readableBytes() : 0;
        if (bodyLen == 0) {
            dst.offerMessage(Http2Frame.data(streamId, endStream ? Http2Flags.END_STREAM : Http2Flags.NONE, new byte[0]));
            if (endStream) {
                this.recordOutboundHalfClosed(context, streamId);
            }
            return;
        }

        int maxFrameSize = this.resolvePeerMaxFrameSize(context);
        int offset = 0;
        while (offset < bodyLen) {
            int chunkLength = Math.min(bodyLen - offset, maxFrameSize);
            byte[] bodyBytes = new byte[chunkLength];
            body.getBytes(offset, bodyBytes, 0, chunkLength);
            int flags = (offset + chunkLength) == bodyLen && endStream ? Http2Flags.END_STREAM : Http2Flags.NONE;
            dst.offerMessage(Http2Frame.data(streamId, flags, bodyBytes));
            offset += chunkLength;
        }
        if (endStream) {
            this.recordOutboundHalfClosed(context, streamId);
        }
    }

    private void sendPing(ProtoContext context, Http2PingEvent event) throws Throwable {
        try {
            byte[] opaqueData = new byte[8];
            event.getData().getBytes(0, opaqueData, 0, opaqueData.length);
            Http2Frame frame = pingFrame(false, opaqueData);
            this.sendEncodedFrame(context, frame);
        } finally {
            event.release();
        }
    }

    private void sendGoaway(ProtoContext context, Http2GoawayEvent event) {
        Http2Frame frame = goawayFrame(event.lastAcceptedId(), event.errorCode(), event.debugData());
        this.sendEncodedFrame(context, frame);
        this.fireEventRcv(context, Http2GoawayEvent.class, event);
    }

    private void sendResetStream(ProtoContext context, Http2ResetEvent resetEvent) {
        long streamId = resetEvent.streamId();
        if (streamId <= 0) {
            return;
        }

        long code = normalizeResetErrorCode(resetEvent.errorCode());
        Http2Frame frame = resetStreamFrame(streamId, code);
        this.sendEncodedFrame(context, frame);
        this.fireEventRcv(context, Http2ResetEvent.class, resetEvent);
    }

    private void sendPriority(ProtoContext context, Http2PriorityEvent event) {
        Http2Frame frame = priorityFrame(event.streamId(), event.streamDependency(), event.weight(), event.exclusive());
        this.sendEncodedFrame(context, frame);
    }

    private void sendPushPromise(ProtoContext context, Http2PushPromiseEvent event) {
        if (!this.serverMode) {
            String msg = "HTTP/2: client endpoint must not send PUSH_PROMISE frames";
            throw new HttpProtocolConnectionException(Math.toIntExact(event.streamId()), Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        int maxFrameSize = this.resolvePeerMaxFrameSize(context);
        List<Http2Frame> frames = pushPromiseFrames(event.streamId(), event.promisedStreamId(), event.headers(), (int) this.localSettings.headerTableSize(), maxFrameSize);
        this.sendEncodedFrames(context, frames);
    }

    private void sendEncodedFrame(ProtoContext context, Http2Frame frame) {
        if (frame != null) {
            context.sendEncoded(this.prepareImmediateControlFrames(context, new Http2Frame[] { frame }));
        }
    }

    private void sendEncodedFrames(ProtoContext context, List<Http2Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        context.sendEncoded(this.prepareImmediateControlFrames(context, frames.toArray(new Http2Frame[0])));
    }

    private Object[] prepareImmediateControlFrames(ProtoContext context, Http2Frame[] frames) {
        Http2EncoderContent state = context.context(Http2EncoderContent.class);
        if (state == null) {
            state = new Http2EncoderContent(this.serverMode, this.localSettings);
            context.context(Http2EncoderContent.class, state);
        }

        if (state.isPrefaceSent()) {
            return frames;
        }

        state.markPrefaceSent();
        List<Object> immediateFrames = new ArrayList<>();
        if (this.serverMode) {
            int initialWindowSize = this.localSettings.initialWindowSize();
            immediateFrames.add(serverSettingsFrame(this.localSettings));
            if (initialWindowSize > 65535) {
                immediateFrames.add(buildWindowUpdateFrame(0, initialWindowSize - 65535));
            }
        } else {
            immediateFrames.add(new Http2Frame(Http2FrameType.PREFACE, 0, 0, CLIENT_PREFACE));
            immediateFrames.add(settingsFrame(false, toSettingsMap(this.localSettings)));
        }

        Collections.addAll(immediateFrames, frames);
        return immediateFrames.toArray(new Object[0]);
    }

    private int resolvePeerMaxFrameSize(ProtoContext context) {
        if (context != null) {
            Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
            if (decoderState != null) {
                return decoderState.remoteMaxFrameSize();
            }
        }

        return DEFAULT_MAX_FRAME_SIZE;
    }

    private void handleStreamError(ProtoContext context, HttpProtocolException protocolError) {
        long streamId = protocolError.getStreamId();
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        if (state != null) {
            state.closeStream(streamId);
            state.removeFromResponseQueue(streamId);
        }

        long errorCode = resolveErrorCode(protocolError.errorCode(), Http2ErrorCode.INTERNAL_ERROR);
        Http2Frame frame = resetStreamFrame(streamId, errorCode);
        this.sendEncodedFrame(context, frame);

        Http2ResetEvent event = new Http2ResetEvent(streamId, errorCode).remote(false);
        this.fireEvent(context, Http2ResetEvent.class, event);
    }

    private void handleConnectionError(ProtoContext context, HttpProtocolException protocolError) {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        long lastAcceptedStreamId = state != null ? Math.max(state.lastEmittedStreamId(), 0) : 0;
        byte[] debugData = buildDebugData(protocolError);
        long errorCode = resolveErrorCode(protocolError.errorCode(), Http2ErrorCode.INTERNAL_ERROR);
        Http2Frame frame = goawayFrame(lastAcceptedStreamId, errorCode, debugData);
        this.sendEncodedFrame(context, frame);

        Http2GoawayEvent event = new Http2GoawayEvent(0, lastAcceptedStreamId, errorCode, debugData).remote(false);
        this.fireEvent(context, Http2GoawayEvent.class, event);
    }

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing HTTP/2 event: " + eventType.getSimpleName(), e);
        }
    }

    private <T> void fireEventRcv(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEventRcv(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing downstream HTTP/2 event: " + eventType.getSimpleName(), e);
        }
    }

    private static Http2Frame buildWindowUpdateFrame(long streamId, int increment) {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((increment >> 24) & 0x7F);
        payload[1] = (byte) ((increment >> 16) & 0xFF);
        payload[2] = (byte) ((increment >> 8) & 0xFF);
        payload[3] = (byte) (increment & 0xFF);
        return Http2Frame.windowUpdate(streamId, payload);
    }

    private static Http2Frame settingsFrame(boolean ack, Map<Integer, Long> settings) {
        if (ack) {
            return Http2Frame.settingsAck();
        }
        int size = settings != null ? settings.size() : 0;
        byte[] payload = new byte[size * 6];
        int offset = 0;
        if (settings != null) {
            for (Map.Entry<Integer, Long> entry : settings.entrySet()) {
                int id = entry.getKey();
                long value = entry.getValue();
                payload[offset++] = (byte) ((id >> 8) & 0xFF);
                payload[offset++] = (byte) (id & 0xFF);
                write32Bits(payload, offset, value);
                offset += 4;
            }
        }
        return Http2Frame.settings(Http2Flags.NONE, payload);
    }

    private static Http2Frame serverSettingsFrame(Http2Settings settings) {
        return settingsFrame(false, toSettingsMap(settings));
    }

    private static Map<Integer, Long> toSettingsMap(Http2Settings settings) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (settings == null) {
            return result;
        }

        if (settings.headerTableSize() != 4096) {
            result.put(Http2Settings.SETTINGS_HEADER_TABLE_SIZE, settings.headerTableSize());
        }
        if (!settings.enablePush()) {
            result.put(Http2Settings.SETTINGS_ENABLE_PUSH, 0L);
        }
        if (settings.maxConcurrentStreams() != Long.MAX_VALUE) {
            result.put(Http2Settings.SETTINGS_MAX_CONCURRENT_STREAMS, settings.maxConcurrentStreams());
        }
        if (settings.initialWindowSize() != 65535) {
            result.put(Http2Settings.SETTINGS_INITIAL_WINDOW_SIZE, (long) settings.initialWindowSize());
        }
        if (settings.maxFrameSize() != DEFAULT_MAX_FRAME_SIZE) {
            result.put(Http2Settings.SETTINGS_MAX_FRAME_SIZE, (long) settings.maxFrameSize());
        }
        if (settings.maxHeaderListSize() != Long.MAX_VALUE) {
            result.put(Http2Settings.SETTINGS_MAX_HEADER_LIST_SIZE, settings.maxHeaderListSize());
        }
        if (settings.enableConnectProtocol()) {
            result.put(Http2Settings.SETTINGS_ENABLE_CONNECT_PROTOCOL, 1L);
        }

        return result;
    }

    private static Http2Frame pingFrame(boolean ack, byte[] opaqueData) {
        if (opaqueData == null || opaqueData.length != 8) {
            String msg = "HTTP/2: ping payload must be exactly 8 bytes";
            throw new HttpProtocolConnectionException(Http2ErrorCode.PROTOCOL_ERROR, msg);
        } else {
            return Http2Frame.ping(ack ? Http2Flags.ACK : Http2Flags.NONE, opaqueData.clone());
        }
    }

    private static Http2Frame resetStreamFrame(long streamId, long errorCode) {
        return Http2Frame.rstStream(streamId, int32(errorCode));
    }

    private static Http2Frame priorityFrame(long streamId, long streamDependency, int weight, boolean exclusive) {
        if (streamId <= 0) {
            String msg = "HTTP/2: PRIORITY streamId must be positive";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (streamDependency < 0) {
            String msg = "HTTP/2: PRIORITY streamDependency must be non-negative";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (streamDependency == streamId) {
            String msg = "HTTP/2: PRIORITY stream cannot depend on itself";
            throw new HttpProtocolStreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (weight < 1 || weight > 256) {
            String msg = "HTTP/2: PRIORITY weight must be between 1 and 256";
            throw new HttpProtocolStreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        byte[] payload = new byte[5];
        int wireDependency = Http2Frame.requireWireStreamId(streamDependency);
        long encodedDependency = exclusive ? (((long) wireDependency) | 0x80000000L) : wireDependency;
        write32Bits(payload, 0, encodedDependency);
        payload[4] = (byte) (weight - 1);
        return Http2Frame.priority(streamId, payload);
    }

    private static Http2Frame goawayFrame(long lastStreamId, long errorCode, byte[] debugData) {
        byte[] safeDebugData = debugData == null ? new byte[0] : debugData.clone();
        byte[] payload = new byte[8 + safeDebugData.length];
        write31Bits(payload, lastStreamId);
        write32Bits(payload, 4, errorCode);
        System.arraycopy(safeDebugData, 0, payload, 8, safeDebugData.length);
        return Http2Frame.goaway(payload);
    }

    private static List<Http2Frame> pushPromiseFrames(long streamId, long promisedStreamId, HttpHeaders headers, int maxHeaderTableSize, int maxFrameSize) {
        if (streamId <= 0 || promisedStreamId <= 0) {
            String msg = "HTTP/2: PUSH_PROMISE streamId and promisedStreamId must be positive";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (headers == null) {
            String msg = "HTTP/2: PUSH_PROMISE headers must not be null";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (maxFrameSize <= 4) {
            String msg = "HTTP/2: PUSH_PROMISE maxFrameSize must be greater than 4";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        HpackEncoder encoder = new HpackEncoder(maxHeaderTableSize);
        encoder.beginEncode();
        for (String headerName : headers.headerNames()) {
            for (String value : headers.getValues(headerName)) {
                encoder.encodeHeaderDirect(headerName.toLowerCase(), value);
            }
        }

        int headerBlockLength = encoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLength];
        System.arraycopy(encoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLength);
        int firstChunkLength = Math.min(headerBlock.length, maxFrameSize - 4);
        byte[] firstPayload = new byte[4 + firstChunkLength];
        write31Bits(firstPayload, promisedStreamId);
        if (firstChunkLength > 0) {
            System.arraycopy(headerBlock, 0, firstPayload, 4, firstChunkLength);
        }
        int firstFlags = firstChunkLength == headerBlock.length ? Http2Flags.END_HEADERS : Http2Flags.NONE;

        List<Http2Frame> frames = new ArrayList<>();
        frames.add(Http2Frame.pushPromise(streamId, firstFlags, firstPayload));

        int offset = firstChunkLength;
        while (offset < headerBlock.length) {
            int chunkLength = Math.min(headerBlock.length - offset, maxFrameSize);
            int flags = (offset + chunkLength) == headerBlock.length ? Http2Flags.END_HEADERS : Http2Flags.NONE;
            frames.add(new Http2Frame(Http2FrameType.CONTINUATION, flags, streamId, headerBlock, offset, chunkLength));
            offset += chunkLength;
        }
        return frames;
    }

    private static long normalizeResetErrorCode(long code) {
        if (code == Http2ResetEvent.CANCEL) {
            return Http2ErrorCode.CANCEL;
        } else if (code == Http2ResetEvent.INTERNAL_ERROR) {
            return Http2ErrorCode.INTERNAL_ERROR;
        } else if (code == Http2ResetEvent.REFUSED) {
            return Http2ErrorCode.REFUSED_STREAM;
        } else {
            return code < 0 ? Http2ErrorCode.INTERNAL_ERROR : code;
        }
    }

    private static int parseWindowUpdateIncrement(byte[] payload, int offset) {
        // @formatter:off
        return ((payload[offset] & 0x7F) << 24) |
               ((payload[offset + 1] & 0xFF) << 16) |
               ((payload[offset + 2] & 0xFF) << 8) |
               (payload[offset + 3] & 0xFF);
        // @formatter:on
    }

    private static void write31Bits(byte[] target, long value) {
        target[0] = (byte) ((value >> 24) & 0x7F);
        target[1] = (byte) ((value >> 16) & 0xFF);
        target[2] = (byte) ((value >> 8) & 0xFF);
        target[3] = (byte) (value & 0xFF);
    }

    private static void write32Bits(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static byte[] int32(long value) {
        byte[] payload = new byte[4];
        write32Bits(payload, 0, value);
        return payload;
    }

    private static long resolveErrorCode(long errorCode, long fallback) {
        return errorCode >= 0 ? errorCode : fallback;
    }

    private static byte[] buildDebugData(HttpProtocolException protocolError) {
        String message = protocolError.getMessage();
        return message == null ? null : message.getBytes(StandardCharsets.UTF_8);
    }

}