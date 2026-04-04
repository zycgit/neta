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
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.HttpRouteKey;

/**
 * Server-side bridge for RFC 7540 h2c upgrade over HTTP/1.1.
 * <p>
 * This handler is designed to sit <b>after</b> the normal HTTP/1.1 request
 * codec on the h2c branch. It only owns the upgrade transaction itself:
 * it buffers staged {@link HttpObject} request parts, decides whether the
 * current request is a legal {@code Upgrade: h2c} exchange, emits the raw
 * HTTP/1.1 {@code 101 Switching Protocols} response and server HTTP/2 SETTINGS
 * preface, promotes stream 1 into a one-shot route seed for the {@code h2}
 * branch, then lets all later traffic continue on the normal {@code h2} route.
 * The route seed is used only for the <b>already consumed upgrade request</b>:
 * once this handler has accepted the HTTP/1.1 upgrade request, that request can
 * no longer be re-decoded by the later {@code h2} branch, so it must be handed
 * off explicitly as the synthetic stream 1 request. The later client HTTP/2
 * connection preface, client SETTINGS, and SETTINGS ACK are <b>not</b> carried
 * by the seed; they flow as normal post-switch HTTP/2 traffic on the {@code h2}
 * branch.
 * <p>
 * Sequence view:
 * <pre>
 *   client                h2c branch                         h2 branch
 *     |                       |                                  |
 *     | HTTP/1.1 upgrade req  |                                  |
 *     |---------------------->| buffer staged request parts      |
 *     |                       | validate Upgrade + Settings      |
 *     |                       | if not upgrade: passthrough      |
 *     |                       |--------------------------------->| normal h1-style handling on h2c branch tail
 *     |                       |
 *     |                       | if valid upgrade request         |
 *     |                       | send "101 Switching Protocols"   |
 *     |<----------------------| send server SETTINGS preface     |
 *     |                       | convert consumed upgrade request |
 *     |                       | to synthetic stream-1 seed       |
 *     |                       | switchRoute(BRANCH_H2, seed)     |
 *     |                       |--------------------------------->| Http2ObjectDuplexe emits seed first
 *     |                       |                                  | stream 1 enters normal h2 pipeline
 *     | client preface        |                                  |
 *     | + client SETTINGS     |                                  |
 *     |---------------------->| routed as normal h2 traffic      |
 *     |                       |--------------------------------->| decoded by Http2Frame/Object path
 *     | SETTINGS ACK          |                                  |
 *     |---------------------->| routed as normal h2 traffic      |
 * </pre>
 * </pre>
 */
public class H2cUpgradeServerDuplexe implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final Logger                   logger               = Logger.getLogger(H2cUpgradeServerDuplexe.class);
    private final        ProtoRoutingControl      control;
    private final        Http2Settings            http2Settings;
    private final        Http2ObjectEncoder       h2ObjectEncoder;
    private final        Http2FrameEncoder        h2FrameEncoder;
    private final        ScratchQueue<Http2Frame> frameScratch;
    private final        ScratchQueue<ByteBuf>    byteScratch;
    private final        List<HttpObject>         bufferedRequestParts = new ArrayList<>();
    private              boolean                  upgraded;

    public H2cUpgradeServerDuplexe(ProtoRoutingControl control) {
        this(control, Http2Settings.defaultLocalSettings(true));
    }

    private H2cUpgradeServerDuplexe(ProtoRoutingControl control, Http2Settings http2Settings) {
        this.control = Objects.requireNonNull(control, "control is null");
        this.http2Settings = http2Settings != null ? new Http2Settings(http2Settings) : Http2Settings.defaultLocalSettings(true);
        this.h2ObjectEncoder = new Http2ObjectEncoder(true, this.http2Settings);
        this.h2FrameEncoder = new Http2FrameEncoder();
        this.frameScratch = new ScratchQueue<>();
        this.byteScratch = new ScratchQueue<>();
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        Http2DecoderContent decoderState = context.rootContext(Http2DecoderContent.class);
        if (decoderState == null) {
            decoderState = new Http2DecoderContent(true, this.http2Settings);
            Http2DecoderContent shared = context.rootContext(Http2DecoderContent.class, decoderState);
            if (shared != null) {
                decoderState = shared;
            }
        }

        if (context.context(Http2DecoderContent.class) == null) {
            context.context(Http2DecoderContent.class, decoderState);
        }

        Http2Context h2Context = context.rootContext(Http2Context.class);
        if (h2Context == null) {
            h2Context = new Http2ContextImpl(true, decoderState);
            Http2Context shared = context.rootContext(Http2Context.class, h2Context);
            if (shared != null) {
                h2Context = shared;
            }
        }

        if (context.context(Http2Context.class) == null) {
            context.context(Http2Context.class, h2Context);
        }

        this.h2ObjectEncoder.onInit(name + "-h2-object-enc", sndSize, context);
        this.h2FrameEncoder.onInit(name + "-h2-frame-enc", sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.h2ObjectEncoder.onActive(context);
        this.h2FrameEncoder.onActive(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,          //
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleUpgradeRequest(context, rcvUp, rcvDown, sndDown);
        } else {
            return this.encodePendingFrames(context, sndUp, sndDown);
        }
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        boolean testEvent = event.getEventType() == Http2PingEvent.class ||     //
                /*        */event.getEventType() == Http2GoawayEvent.class ||   //
                /*        */event.getEventType() == Http2ResetEvent.class ||    //
                /*        */event.getEventType() == Http2PriorityEvent.class || //
                /*        */event.getEventType() == Http2PushPromiseEvent.class;
        if (this.upgraded && testEvent) {
            return this.h2ObjectEncoder.onEvent(context, event);
        } else {
            return true;
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (!this.upgraded) {
            return ProtoStatus.Next;
        }

        if (isRcv) {
            return ProtoStatus.Next;
        }

        return this.h2ObjectEncoder.onError(context, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetRequestState(true);
        this.frameScratch.clear();
        this.byteScratch.clear();
        this.h2ObjectEncoder.onClose(context);
        this.h2FrameEncoder.onClose(context);
    }

    //

    private ProtoStatus handleUpgradeRequest(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        while (rcvUp.hasMore()) {
            HttpObject message = rcvUp.peekMessage();
            if (message == null) {
                rcvUp.takeMessage();
                continue;
            }

            if (this.upgraded) {
                throw new HttpProtocolStateException("HTTP/2: h2c upgrade branch should switch to the h2 route before receiving post-upgrade inbound payload");
            }

            if (this.bufferedRequestParts.isEmpty() && !(message instanceof HttpRequest)) {
                rcvDown.offerMessage(rcvUp.takeMessage());
                continue;
            }

            message = rcvUp.takeMessage();
            this.bufferedRequestParts.add(message);

            if (!isHeaderSectionClosed(message)) {
                if (isRequestComplete(message)) {
                    this.flushBufferedRequest(rcvDown);
                    this.resetRequestState(false);
                }
                continue;
            }

            boolean upgradeRequest = isValidUpgradeRequest(this.bufferedRequestParts);
            boolean requestComplete = isRequestComplete(message);
            if (!requestComplete) {
                if (!upgradeRequest) {
                    this.flushBufferedRequest(rcvDown);
                    this.resetRequestState(false);
                }
                continue;
            }

            if (upgradeRequest) {
                FullHttpRequest request = this.buildFullHttpRequest(context.byteBufAllocator());
                this.performUpgrade(context, request, sndDown);
                this.resetRequestState(true);
                return ProtoStatus.Stop;
            }

            this.flushBufferedRequest(rcvDown);
            this.resetRequestState(false);
        }

        return ProtoStatus.Next;
    }

    private boolean isValidUpgradeRequest(List<HttpObject> requestParts) {
        HttpRequest requestLine = this.findRequestLine(requestParts);
        if (requestLine == null || !HttpVersion.HTTP_1_1.equals(requestLine.protocolVersion())) {
            return false;
        }

        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.H2C, this.getHeader(requestParts, HttpHeaderNames.UPGRADE))) {
            return false;
        }

        List<String> headers = this.getHeaders(requestParts, HttpHeaderNames.HTTP2_SETTINGS);
        if (headers.size() != 1 || StringUtils.isBlank(headers.get(0))) {
            return false;
        }

        String c = this.getHeader(requestParts, HttpHeaderNames.CONNECTION);
        return containsConnectionToken(c, HttpHeaderValues.UPGRADE) && containsConnectionToken(c, HttpHeaderNames.HTTP2_SETTINGS);
    }

    private FullHttpRequest buildFullHttpRequest(ByteBufAllocator alloc) {
        HttpRequest requestLine = this.findRequestLine(this.bufferedRequestParts);
        if (requestLine == null) {
            throw new HttpProtocolStateException("HTTP/2: incomplete h2c request state");
        }

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        CompositeByteBuf content = null;
        for (HttpObject part : this.bufferedRequestParts) {
            if (part instanceof HttpHeaders) {
                headers.appendHeaders((HttpHeaders) part);
            }
            if (part instanceof HttpContent) {
                ByteBuf bodyPart = ((HttpContent) part).content();
                if (bodyPart != null && bodyPart.readableBytes() > 0) {
                    if (content == null) {
                        content = ByteBufUtils.compositeBuffer(alloc);
                    }
                    content.addComponent(this.copyContent(bodyPart));
                }
            }
        }

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(requestLine.protocolVersion(), requestLine.method(), requestLine.uri(), content == null ? ByteBuf.EMPTY : content, headers);
        request.streamId(requestLine.streamId());
        return request;
    }

    private void performUpgrade(ProtoContext context, FullHttpRequest request, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        byte[] settingsPayload = decodeSettingsPayload(request.getString(HttpHeaderNames.HTTP2_SETTINGS));
        applyRemoteSettings(context, settingsPayload);
        sendSwitchingProtocols(context, sndDown);
        sendServerPreface(context, sndDown);
        this.upgraded = true;

        context.fireEventSnd(HttpThroughEvent.class, HttpThroughEvent.enable());
        promoteRequestToHttp2(context, request);
        switchToHttp2Route(request);
        if (context.getConfig().isPrintLog()) {
            logger.info("[H2C-UPGRADE] channel=" + context.getChannel().getChannelId() + " upgraded request to stream=1 uri=" + request.uri());
        }
    }

    private void switchToHttp2Route(FullHttpRequest request) {
        this.control.switchRoute(HttpRouteKey.BRANCH_H2, request);
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

    private void sendSwitchingProtocols(ProtoContext context, ProtoSndQueue<HttpObject> sndDown) {
        ByteBuf responseBytes = context.byteBufAllocator().buffer(128);
        byte[] payload = ("HTTP/1.1 101 Switching Protocols\r\n" + "Connection: Upgrade\r\n" + "Upgrade: h2c\r\n" + "Content-Length: 0\r\n" + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        responseBytes.writeBytes(payload);
        responseBytes.markWriter();
        sndDown.offerMessage(new DefaultHttpByteBuf(responseBytes));
    }

    private void sendServerPreface(ProtoContext context, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        this.frameScratch.clear();
        this.byteScratch.clear();
        try {
            this.h2ObjectEncoder.flushPendingFrames(context, this.frameScratch);
            if (!this.frameScratch.hasMore()) {
                return;
            }

            this.h2FrameEncoder.onMessage(context, this.frameScratch, this.byteScratch);
            while (this.byteScratch.hasMore()) {
                ByteBuf payload = this.byteScratch.takeMessage();
                if (payload != null) {
                    sndDown.offerMessage(new DefaultHttpByteBuf(payload));
                }
            }
        } finally {
            this.frameScratch.clear();
            this.byteScratch.clear();
        }
    }

    private void promoteRequestToHttp2(ProtoContext context, FullHttpRequest request) {
        FullHttpRequest fullRequest = request;
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

    private void flushBufferedRequest(ProtoSndQueue<HttpObject> rcvDown) {
        rcvDown.offerMessage(this.bufferedRequestParts);
    }

    private void resetRequestState(boolean releaseBuffered) {
        if (releaseBuffered) {
            for (HttpObject msg : this.bufferedRequestParts) {
                SoUtils.release(msg);
            }
        }
        this.bufferedRequestParts.clear();
    }

    private ProtoStatus encodePendingFrames(ProtoContext context, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        this.frameScratch.clear();
        this.byteScratch.clear();
        try {
            this.h2ObjectEncoder.onMessage(context, sndUp, this.frameScratch);
            if (!this.frameScratch.hasMore()) {
                return ProtoStatus.Next;
            }

            this.h2FrameEncoder.onMessage(context, this.frameScratch, this.byteScratch);
            while (this.byteScratch.hasMore()) {
                ByteBuf payload = this.byteScratch.takeMessage();
                if (payload != null) {
                    sndDown.offerMessage(new DefaultHttpByteBuf(payload));
                }
            }

            return ProtoStatus.Next;
        } finally {
            this.frameScratch.clear();
            this.byteScratch.clear();
        }
    }

    // Utils

    private static boolean isHeaderSectionClosed(HttpObject msg) {
        return msg instanceof LastHttpHeaders || (msg instanceof HttpRequest && msg instanceof HttpContent);
    }

    private static boolean isRequestComplete(HttpObject msg) {
        return msg instanceof LastHttpContent || (msg instanceof HttpRequest && msg instanceof HttpContent);
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

    private HttpRequest findRequestLine(List<HttpObject> requestParts) {
        for (HttpObject part : requestParts) {
            if (part instanceof HttpRequest) {
                return (HttpRequest) part;
            }
        }
        return null;
    }

    private String getHeader(List<HttpObject> requestParts, String name) {
        for (HttpObject part : requestParts) {
            if (part instanceof HttpHeaders) {
                String value = ((HttpHeaders) part).getString(name);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private List<String> getHeaders(List<HttpObject> requestParts, String name) {
        ArrayList<String> values = new ArrayList<>();
        for (HttpObject part : requestParts) {
            if (part instanceof HttpHeaders) {
                values.addAll(((HttpHeaders) part).getValues(name));
            }
        }
        return values;
    }

    private ByteBuf copyContent(ByteBuf source) {
        int length = source.readableBytes();
        if (length == 0) {
            return ByteBuf.EMPTY;
        }
        byte[] copied = new byte[length];
        source.getBytes(0, copied, 0, length);
        ByteBuf target = ByteBufAllocator.DEFAULT.buffer(length, Integer.MAX_VALUE);
        target.writeBytes(copied, 0, copied.length);
        target.markWriter();
        return target;
    }

    /** Scratch queue used only for the local outbound HTTP/2 encoding step. */
    private static class ScratchQueue<T> implements ProtoRcvQueue<T>, ProtoSndQueue<T> {
        @SuppressWarnings("rawtypes")
        private static final ProtoSndQueue EMPTY_SND = new ProtoSndQueue() {
            @Override
            public int getCapacity() {
                return 0;
            }

            @Override
            public int slotSize() {
                return 0;
            }

            @Override
            public boolean offerMessage(Object[] offerList) {
                return false;
            }

            @Override
            public boolean offerMessage(List offerList) {
                return false;
            }

            @Override
            public boolean offerMessage(ProtoRcvQueue offerList) {
                return false;
            }
        };

        private final List<T> list = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static <T> ProtoSndQueue<T> emptySnd() {
            return (ProtoSndQueue<T>) EMPTY_SND;
        }

        @Override
        public int getCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int slotSize() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean offerMessage(T[] offerList) {
            if (offerList == null || offerList.length == 0) {
                return false;
            }
            Collections.addAll(this.list, offerList);
            return true;
        }

        @Override
        public boolean offerMessage(List<T> offerList) {
            if (offerList == null || offerList.isEmpty()) {
                return false;
            }
            this.list.addAll(offerList);
            return true;
        }

        @Override
        public boolean offerMessage(T offerMessage) {
            this.list.add(offerMessage);
            return true;
        }

        @Override
        public boolean offerMessage(ProtoRcvQueue<T> offerList) {
            if (offerList == null || !offerList.hasMore()) {
                return false;
            }
            while (offerList.hasMore()) {
                this.list.add(offerList.takeMessage());
            }
            return true;
        }

        @Override
        public int queueSize() {
            return this.list.size();
        }

        @Override
        public List<T> takeMessage(int cnt) {
            if (this.list.isEmpty()) {
                return Collections.emptyList();
            }
            if (cnt < 0) {
                cnt = this.list.size();
            }
            int take = Math.min(cnt, this.list.size());
            List<T> result = new ArrayList<>(this.list.subList(0, take));
            this.list.subList(0, take).clear();
            return result;
        }

        @Override
        public List<T> peekMessage(int cnt) {
            if (this.list.isEmpty()) {
                return Collections.emptyList();
            }
            if (cnt < 0) {
                cnt = this.list.size();
            }
            int take = Math.min(cnt, this.list.size());
            return new ArrayList<>(this.list.subList(0, take));
        }

        @Override
        public void skipMessage(int cnt) {
            if (cnt < 0) {
                cnt = this.list.size();
            }
            int skip = Math.min(cnt, this.list.size());
            for (int i = 0; i < skip; i++) {
                SoUtils.release(this.list.get(i));
            }
            this.list.subList(0, skip).clear();
        }

        void clear() {
            this.skipMessage(this.list.size());
        }
    }
}