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
import java.util.Base64;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * Server-side bridge for RFC 7540 h2c upgrade over HTTP/1.1.
 * <p>
 * The handler starts in HTTP/1.1 mode, aggregates the upgrade request,
 * emits a 101 response plus server HTTP/2 SETTINGS, then switches the
 * connection into the normal {@link Http2ObjectDuplexe} path.
 */
public class H2cUpgradeServerDuplexe implements ProtoDuplexer<ByteBuf, HttpObject, HttpObject, ByteBuf> {
    private static final Logger                logger = Logger.getLogger(H2cUpgradeServerDuplexe.class);
    private final        HttpServerDuplexe     http1Codec;
    private final        HttpRequestAggregator http1Aggregator;
    private final        Http2FrameDuplexe     http2FrameCodec;
    private final        Http2ObjectDuplexe    http2ObjectCodec;
    private              boolean               upgraded;

    public H2cUpgradeServerDuplexe() {
        this(new HttpServerDuplexe(), new HttpRequestAggregator(1048576), new Http2FrameDuplexe(true), new Http2ObjectDuplexe(true));
    }

    public H2cUpgradeServerDuplexe(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, int maxContentLength) {
        this(new HttpServerDuplexe(maxInitialLineLength, maxHeaderSize, maxChunkSize), new HttpRequestAggregator(maxContentLength), new Http2FrameDuplexe(true), new Http2ObjectDuplexe(true, Http2Settings.defaultLocalSettings(true, maxHeaderSize, maxContentLength)));
    }

    private H2cUpgradeServerDuplexe(HttpServerDuplexe http1Codec, HttpRequestAggregator http1Aggregator, Http2FrameDuplexe http2FrameCodec, Http2ObjectDuplexe http2ObjectCodec) {
        this.http1Codec = http1Codec;
        this.http1Aggregator = http1Aggregator;
        this.http2FrameCodec = http2FrameCodec;
        this.http2ObjectCodec = http2ObjectCodec;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.http1Codec.onInit(name + "-http1", rcvSize, sndSize, context);
        this.http1Aggregator.onInit(name + "-http1-agg", rcvSize, context);
        this.http2FrameCodec.onInit(name + "-h2-frame", rcvSize, sndSize, context);
        this.http2ObjectCodec.onInit(name + "-h2-object", rcvSize, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.http1Codec.onActive(context);
        this.http2FrameCodec.onActive(context);
        this.http2ObjectCodec.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,       //
            ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        if (isRcv) {
            if (this.upgraded) {
                ProtoQueue<Http2Frame> inboundFrames = new ProtoQueue<>(-1);
                ProtoQueue<Http2Frame> outboundFrames = new ProtoQueue<>(-1);
                ProtoStatus status = this.http2FrameCodec.onMessage(context, true, rcvUp, inboundFrames, new ProtoQueue<Http2Frame>(-1), sndDown);
                this.http2ObjectCodec.onMessage(context, true, inboundFrames, rcvDown, new ProtoQueue<HttpObject>(-1), outboundFrames);
                if (outboundFrames.hasMore()) {
                    this.http2FrameCodec.onMessage(context, false, new ProtoQueue<ByteBuf>(-1), new ProtoQueue<Http2Frame>(-1), outboundFrames, sndDown);
                }
                return status;
            }
            return this.handleUpgradeRequest(context, rcvUp, rcvDown, sndDown);
        }

        if (this.upgraded) {
            ProtoQueue<Http2Frame> outboundFrames = new ProtoQueue<>(-1);
            this.http2ObjectCodec.onMessage(context, false, new ProtoQueue<Http2Frame>(-1), new ProtoQueue<HttpObject>(-1), sndUp, outboundFrames);
            return this.http2FrameCodec.onMessage(context, false, new ProtoQueue<ByteBuf>(-1), new ProtoQueue<Http2Frame>(-1), outboundFrames, sndDown);
        }
        return this.http1Codec.onMessage(context, false, rcvUp, rcvDown, sndUp, sndDown);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (this.upgraded) {
            return this.http2FrameCodec.onEvent(context, event, isRcv) && this.http2ObjectCodec.onEvent(context, event, isRcv);
        } else {
            return true;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (this.upgraded) {
            if (isRcv) {
                return this.http2FrameCodec.onError(context, true, e, eh);
            }
            return this.http2ObjectCodec.onError(context, false, e, eh);
        }
        return this.http1Codec.onError(context, isRcv, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.http1Codec.onClose(context);
        this.http1Aggregator.onClose(context);
        this.http2ObjectCodec.onClose(context);
        this.http2FrameCodec.onClose(context);
    }

    private ProtoStatus handleUpgradeRequest(ProtoContext context, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        ProtoQueue<HttpObject> decodedQueue = new ProtoQueue<>(-1);
        this.http1Codec.onMessage(context, true, rcvUp, decodedQueue, new ProtoQueue<HttpObject>(-1), sndDown);

        if (decodedQueue.queueSize() == 0) {
            return ProtoStatus.Next;
        }

        ProtoQueue<HttpObject> aggregatedQueue = new ProtoQueue<>(-1);
        this.http1Aggregator.onMessage(context, decodedQueue, aggregatedQueue);

        while (aggregatedQueue.hasMore()) {
            HttpObject message = aggregatedQueue.takeMessage();
            if (message instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) message;
                if (isValidUpgradeRequest(request)) {
                    performUpgrade(context, request, rcvDown, sndDown);
                    continue;
                }
            }
            rcvDown.offerMessage(message);
        }
        return ProtoStatus.Next;
    }

    private boolean isValidUpgradeRequest(FullHttpRequest request) {
        if (!HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.H2C, request.getString(HttpHeaderNames.UPGRADE))) {
            return false;
        }
        List<String> settingsHeaders = request.getValues(HttpHeaderNames.HTTP2_SETTINGS);
        if (settingsHeaders.size() != 1 || StringUtils.isBlank(settingsHeaders.get(0))) {
            return false;
        }

        String connection = request.getString(HttpHeaderNames.CONNECTION);
        return containsConnectionToken(connection, HttpHeaderValues.UPGRADE) && containsConnectionToken(connection, HttpHeaderNames.HTTP2_SETTINGS);
    }

    private void performUpgrade(ProtoContext context, FullHttpRequest request, ProtoSndQueue<HttpObject> rcvDown, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        byte[] settingsPayload = decodeSettingsPayload(request.getString(HttpHeaderNames.HTTP2_SETTINGS));
        applyRemoteSettings(context, settingsPayload);
        sendSwitchingProtocols(context, sndDown);
        this.upgraded = true;
        sendServerPreface(context, sndDown);
        promoteRequestToHttp2(context, request);
        rcvDown.offerMessage(request);
        if (context.getConfig().isPrintLog()) {
            logger.info("[H2C-UPGRADE] channel=" + context.getChannel().getChannelId() + " upgraded request to stream=1 uri=" + request.uri());
        }
    }

    private byte[] decodeSettingsPayload(String encodedSettings) {
        String value = encodedSettings.trim();
        int padding = value.length() % 4;
        if (padding != 0) {
            value = value + "====".substring(padding);
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value);
            if (payload.length % 6 != 0) {
                throw new HttpBadRequestException("HTTP/2: invalid HTTP2-Settings payload length " + payload.length);
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw new HttpBadRequestException("HTTP/2: invalid HTTP2-Settings header", e);
        }
    }

    private void applyRemoteSettings(ProtoContext context, byte[] settingsPayload) {
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        for (int i = 0; i < settingsPayload.length; i += 6) {
            int id = ((settingsPayload[i] & 0xFF) << 8) | (settingsPayload[i + 1] & 0xFF);
            long value = ((settingsPayload[i + 2] & 0xFFL) << 24) | ((settingsPayload[i + 3] & 0xFFL) << 16) | ((settingsPayload[i + 4] & 0xFFL) << 8) | (settingsPayload[i + 5] & 0xFFL);
            decoderState.applyRemoteSetting(id, value);
        }
        Http2Stream stream = decoderState.getOrCreateStream(1);
        stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
        decoderState.offerResponseStreamId(1);
    }

    private void sendSwitchingProtocols(ProtoContext context, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.H2C);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);

        ProtoQueue<HttpObject> responseQueue = new ProtoQueue<>(-1);
        responseQueue.offerMessage(response);
        this.http1Codec.onMessage(context, false, new ProtoQueue<ByteBuf>(-1), new ProtoQueue<HttpObject>(-1), responseQueue, sndDown);
    }

    private void sendServerPreface(ProtoContext context, ProtoSndQueue<ByteBuf> sndDown) throws Throwable {
        ProtoQueue<Http2Frame> outboundFrames = new ProtoQueue<>(-1);
        this.http2ObjectCodec.onMessage(context, true, new ProtoQueue<Http2Frame>(-1), new ProtoQueue<HttpObject>(-1), new ProtoQueue<HttpObject>(-1), outboundFrames);
        if (outboundFrames.hasMore()) {
            this.http2FrameCodec.onMessage(context, false, new ProtoQueue<ByteBuf>(-1), new ProtoQueue<Http2Frame>(-1), outboundFrames, sndDown);
        }
    }

    private void promoteRequestToHttp2(ProtoContext context, FullHttpRequest request) {
        if (!(request instanceof DefaultFullHttpRequest)) {
            throw new HttpProtocolStateException("HTTP/2: h2c upgrade requires DefaultFullHttpRequest");
        }
        DefaultFullHttpRequest fullRequest = (DefaultFullHttpRequest) request;
        fullRequest.removeHeader(HttpHeaderNames.CONNECTION);
        fullRequest.removeHeader(HttpHeaderNames.UPGRADE);
        fullRequest.removeHeader(HttpHeaderNames.HTTP2_SETTINGS);
        if (StringUtils.isBlank(fullRequest.getString(HttpHeaderNames.X_FORWARDED_PROTO))) {
            fullRequest.setHeader(HttpHeaderNames.X_FORWARDED_PROTO, "http");
        }
        fullRequest.protocolVersion(HttpVersion.HTTP_2_0);
        fullRequest.streamId(1);
        Http2DecoderContent decoderState = context.context(Http2DecoderContent.class);
        decoderState.setLastEmittedStreamId(1);
    }

    private boolean containsConnectionToken(String headerValue, String token) {
        if (headerValue == null || token == null) {
            return false;
        }
        String[] tokens = headerValue.split(",");
        for (String item : tokens) {
            if (token.equalsIgnoreCase(item.trim())) {
                return true;
            }
        }
        return false;
    }
}