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
package net.hasor.neta.codec.http.websocket;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.event.HttpThroughEvent;

/**
 * Client-side WebSocket opening-handshake duplexer.
 * <p>
 * This duplexer is the client-side counterpart to {@link WebSocketServerDuplexer}.
 * It observes the outbound staged or aggregated HTTP upgrade request, records the
 * handshake metadata needed to validate the server's HTTP 101 response, then on the
 * receive side consumes the staged or aggregated handshake response, switches the shared {@link HttpContext} into
 * transparent mode, installs {@link WebSocketContext}, and emits a synthetic
 * {@link HandshakeWebSocketMessage} downstream. After the handshake is complete it
 * becomes a transparent pass-through node for both directions.
 * <p>
 * The duplexer does not synthesize the client upgrade request on its own. Request
 * construction remains the caller's responsibility so it can still control URI,
 * headers, cookies, and sub-protocol negotiation.
 * <p>
 * The configured codec version controls which framing family may proceed past the
 * handshake gate:
 * <ul>
 *   <li>{@link WebSocketVersion#V0} accepts only Hixie-76 framing.</li>
 *   <li>{@link WebSocketVersion#V7}, {@link WebSocketVersion#V8}, and
 *       {@link WebSocketVersion#V13} all accept RFC 6455 framing family handshakes.</li>
 * </ul>
 * <p><b>Pipeline placement:</b>
 * <pre>
 *   ctx.addLast("http", new HttpClientDuplexe());
 *   ctx.addLast("ws-handshake", new WebSocketClientDuplexer(WebSocketVersion.V13));
 *   ctx.addLast("ws-io", new WebSocketClientDuplexeAggregator(WebSocketVersion.V13));
 * </pre>
 * <p><b>Ownership:</b> outbound upgrade requests are observed but not consumed by this
 * duplexer. The HTTP 101 response is consumed and released after validation and after
 * the synthetic handshake-complete message has been produced.
 */
public class WebSocketClientDuplexer implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final class HandshakeState {
        private boolean          ready;
        private boolean          requestPending;
        private WebSocketVersion requestedVersion;
        private String           requestPath;
        private String           requestedProtocols;
        private String           requestKey;
        private String           requestKey1;
        private String           requestKey2;
        private byte[]           requestKey3;
        private final WebSocketHandshakeHttpCollector.RequestCollector  requestCollector  = new WebSocketHandshakeHttpCollector.RequestCollector();
        private final WebSocketHandshakeHttpCollector.ResponseCollector responseCollector = new WebSocketHandshakeHttpCollector.ResponseCollector();
        private final java.util.ArrayList<HttpObject>                   bufferedResponses = new java.util.ArrayList<>();
        private WebSocketVersion                                        observingVersion;
    }

    private final WebSocketVersion codecVersion;

    /** Creates a client handshake duplexer for RFC 6455 version 13 framing. */
    public WebSocketClientDuplexer() {
        this(WebSocketVersion.V13);
    }

    /**
     * Creates a client handshake duplexer for the specified framing family.
     * @param codecVersion the framing version used by the downstream frame codec
     */
    public WebSocketClientDuplexer(WebSocketVersion codecVersion) {
        if (codecVersion == null) {
            throw new IllegalArgumentException("codecVersion must not be null");
        }
        this.codecVersion = codecVersion;
    }

    /** Returns the framing version family guarded by this duplexer. */
    public WebSocketVersion getCodecVersion() {
        return this.codecVersion;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        state(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        return isRcv ? handleRcv(context, rcvUp, rcvDown) : handleSnd(context, sndUp, sndDown);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        HandshakeState state = state(context);
        state.requestCollector.reset();
        resetBufferedResponses(state);
        return ProtoStatus.Next;
    }

    private ProtoStatus handleRcv(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            boolean consumed = false;
            try {
                if (!state.ready && state.requestPending && msg instanceof FullHttpResponse) {
                    consumed = true;
                    handleUpgradeResponse(context, state, (FullHttpResponse) msg, dst);
                    continue;
                }

                if (!state.ready && state.requestPending && handleStagedResponse(context, state, msg, dst)) {
                    continue;
                }

                if (!state.ready && isWebSocketTraffic(msg)) {
                    throw new WebSocketProtocolViolationException("websocket handshake is not complete; inbound WebSocket traffic is not allowed yet.");
                }

                dst.offerMessage(msg);
            } finally {
                if (consumed) {
                    msg.release();
                }
            }
        }
        return ProtoStatus.Next;
    }

    private ProtoStatus handleSnd(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        HandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (!state.ready && msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;
                WebSocketVersion requestedVersion = WebSocketServerDuplexerHelper.detectVersion(request);
                if (requestedVersion != null) {
                    recordUpgradeRequest(state, request, requestedVersion);
                }
            } else if (!state.ready) {
                observeStagedRequest(state, msg);
            }

            if (!state.ready && isWebSocketTraffic(msg)) {
                throw new WebSocketProtocolViolationException("websocket handshake is not complete; outbound WebSocket traffic is not allowed yet.");
            }

            dst.offerMessage(msg);
        }
        return ProtoStatus.Next;
    }

    private boolean handleStagedResponse(ProtoContext context, HandshakeState state, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        if (state.responseCollector.isActive() || msg instanceof HttpResponse) {
            state.bufferedResponses.add(msg);
            state.responseCollector.append(msg);
            if (state.responseCollector.isComplete()) {
                FullHttpResponse response = state.responseCollector.snapshot();
                try {
                    handleUpgradeResponse(context, state, response, dst);
                } finally {
                    response.release();
                    resetBufferedResponses(state);
                }
            }
            return true;
        }
        return false;
    }

    private void observeStagedRequest(HandshakeState state, HttpObject msg) {
        if (!(state.requestCollector.isActive() || msg instanceof HttpRequest)) {
            return;
        }
        state.requestCollector.append(msg);
        if (state.observingVersion == null && state.requestCollector.isHeadersClosed()) {
            FullHttpRequest snapshot = state.requestCollector.snapshot();
            try {
                state.observingVersion = snapshot != null ? WebSocketServerDuplexerHelper.detectVersion(snapshot) : null;
            } finally {
                if (snapshot != null) {
                    snapshot.release();
                }
            }
            if (state.observingVersion == null) {
                state.requestCollector.reset();
                return;
            }
        }
        if (state.observingVersion != null && state.requestCollector.isComplete()) {
            FullHttpRequest request = state.requestCollector.snapshot();
            try {
                recordUpgradeRequest(state, request, state.observingVersion);
            } finally {
                request.release();
                state.requestCollector.reset();
                state.observingVersion = null;
            }
        }
    }

    private void recordUpgradeRequest(HandshakeState state, FullHttpRequest request, WebSocketVersion requestedVersion) {
        if (state.requestPending) {
            throw new WebSocketProtocolViolationException("websocket handshake request is already in progress.");
        }
        if (!isCompatible(requestedVersion)) {
            throw new WebSocketProtocolViolationException("websocket handshake version " + requestedVersion + " is incompatible with frame codec version " + this.codecVersion + '.');
        }

        state.requestPending = true;
        state.requestedVersion = requestedVersion;
        state.requestPath = request.uri();
        state.requestedProtocols = request.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        state.requestKey = null;
        state.requestKey1 = null;
        state.requestKey2 = null;
        state.requestKey3 = null;

        if (requestedVersion.isRfc6455Framing()) {
            String requestKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            if (StringUtils.isBlank(requestKey)) {
                throw new IllegalArgumentException("Missing Sec-WebSocket-Key header");
            }
            state.requestKey = requestKey.trim();
        } else {
            String key1 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
            String key2 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
            if (StringUtils.isBlank(key1) || StringUtils.isBlank(key2)) {
                throw new IllegalArgumentException("Missing Sec-WebSocket-Key1 or Sec-WebSocket-Key2 header");
            }

            ByteBuf body = request.content();
            if (body == null || body.readableBytes() < 8) {
                throw new IllegalArgumentException("Hixie-76 handshake requires 8-byte body");
            }

            byte[] key3 = new byte[8];
            body.getBytes(0, key3, 0, 8);
            state.requestKey1 = key1;
            state.requestKey2 = key2;
            state.requestKey3 = key3;
        }
    }

    private void handleUpgradeResponse(ProtoContext context, HandshakeState state, FullHttpResponse response, ProtoSndQueue<HttpObject> dst) throws Throwable {
        validateUpgradeResponseHeaders(response);
        if (state.requestedVersion.isRfc6455Framing()) {
            validateRfc6455Response(state, response);
        } else {
            validateHixie76Response(state, response);
        }

        String subProtocol = response.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        validateSubProtocol(state.requestedProtocols, subProtocol);
        String extensions = response.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

        ((NetChannel) context.getChannel()).fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
        context.context(WebSocketContext.class, WebSocketContextImpl.fromClientHandshake(state.requestedVersion, state.requestPath, subProtocol, extensions));

        state.ready = true;
        state.requestPending = false;
        dst.offerMessage(new HandshakeWebSocketMessage(state.requestedVersion, state.requestPath, subProtocol, extensions));
    }

    private void validateUpgradeResponseHeaders(FullHttpResponse response) {
        if (response.status() == null || response.status().code() != HttpStatus.SWITCHING_PROTOCOLS.code()) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: expected HTTP 101 Switching Protocols response.");
        }

        String upgrade = response.getString(HttpHeaderNames.UPGRADE);
        String connection = response.getString(HttpHeaderNames.CONNECTION);
        if (!StringUtils.containsIgnoreCase(connection, HttpHeaderValues.UPGRADE)) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: missing Connection: Upgrade response header.");
        }
        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && !StringUtils.equalsIgnoreCase("WebSocket", upgrade)) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: missing Upgrade: websocket response header.");
        }
    }

    private void validateRfc6455Response(HandshakeState state, FullHttpResponse response) {
        String acceptKey = response.getString(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT);
        String expected = WebSocketServerDuplexerHelper.computeAcceptKey(state.requestKey);
        if (!StringUtils.equals(expected, acceptKey)) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: Sec-WebSocket-Accept does not match the client handshake key.");
        }
    }

    private void validateHixie76Response(HandshakeState state, FullHttpResponse response) {
        byte[] expected = WebSocketServerDuplexerHelper.computeHixie76Response(state.requestKey1, state.requestKey2, state.requestKey3);
        ByteBuf content = response.content();
        if (content == null || content.readableBytes() != expected.length) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: invalid Hixie-76 challenge response body.");
        }

        byte[] actual = new byte[expected.length];
        content.getBytes(0, actual, 0, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new WebSocketProtocolViolationException("websocket upgrade failed: invalid Hixie-76 challenge response body.");
            }
        }
    }

    private void validateSubProtocol(String requestedProtocols, String selectedProtocol) {
        if (StringUtils.isBlank(selectedProtocol)) {
            return;
        }
        if (StringUtils.isBlank(requestedProtocols)) {
            throw new WebSocketProtocolViolationException("websocket upgrade failed: server selected an unexpected sub-protocol.");
        }

        String[] candidates = requestedProtocols.split(",");
        for (String candidate : candidates) {
            if (selectedProtocol.equals(candidate.trim())) {
                return;
            }
        }
        throw new WebSocketProtocolViolationException("websocket upgrade failed: server selected a sub-protocol that was not requested by the client.");
    }

    private boolean isCompatible(WebSocketVersion requestedVersion) {
        if (requestedVersion == this.codecVersion) {
            return true;
        }
        return requestedVersion != null && requestedVersion.isRfc6455Framing() && this.codecVersion.isRfc6455Framing();
    }

    private boolean isWebSocketTraffic(HttpObject msg) {
        return msg instanceof WebSocketFrame || msg instanceof WebSocketMessage;
    }

    private HandshakeState state(ProtoContext context) {
        HandshakeState state = context.context(HandshakeState.class);
        if (state == null) {
            state = new HandshakeState();
            context.context(HandshakeState.class, state);
        }
        return state;
    }

    private void resetBufferedResponses(HandshakeState state) {
        for (HttpObject buffered : state.bufferedResponses) {
            buffered.release();
        }
        state.bufferedResponses.clear();
        state.responseCollector.reset();
    }

    @Override
    public void onClose(ProtoContext context) {
        HandshakeState state = state(context);
        state.requestCollector.reset();
        resetBufferedResponses(state);
    }
}