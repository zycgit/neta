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
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.event.HttpThroughEvent;

/**
 * Server-side WebSocket opening-handshake duplexer.
 * <p>
 * This duplexer is intended to sit after {@link HttpServerDuplexe} and before
 * {@link WebSocketFrameDecoder}/{@link WebSocketFrameEncoder}. On the receive side
 * it consumes a staged or aggregated HTTP upgrade request, sends the HTTP 101 response, switches the shared {@link HttpContext} into transparent mode, installs
 * {@link WebSocketContext}, and emits a synthetic {@link HandshakeWebSocketMessage}
 * downstream. After the handshake is complete it becomes a transparent pass-through
 * node for both directions.
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
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLast("ws-handshake", new WebSocketServerDuplexer(WebSocketVersion.V13));
 *   ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(WebSocketVersion.V13));
 *   ctx.addLastDecoder("ws-agg", new WebSocketFrameAggregator());
 *   ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(WebSocketVersion.V13));
 * </pre>
 * <p><b>Ownership:</b> when an upgrade request is consumed by this duplexer, the
 * duplexer takes over that HTTP message lifecycle and releases the consumed request parts after
 * the handshake response and synthetic completion message have been produced.
 */
public class WebSocketServerDuplexer implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final class HandshakeState {
        private boolean                                          ready;
        private WebSocketVersion                                 pendingVersion;
        private final WebSocketHandshakeHttpCollector.RequestCollector collector = new WebSocketHandshakeHttpCollector.RequestCollector();
        private final java.util.ArrayList<HttpObject>            bufferedMessages = new java.util.ArrayList<>();
    }

    private final WebSocketVersion codecVersion;

    /** Creates a server handshake duplexer for RFC 6455 version 13 framing. */
    public WebSocketServerDuplexer() {
        this(WebSocketVersion.V13);
    }

    /**
     * Creates a server handshake duplexer for the specified framing family.
     * @param codecVersion the framing version used by the downstream frame codec
     */
    public WebSocketServerDuplexer(WebSocketVersion codecVersion) {
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
        if (isRcv) {
            resetHandshakeState(state(context), true);
        }
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
                if (!state.ready && msg instanceof FullHttpRequest) {
                    FullHttpRequest request = (FullHttpRequest) msg;
                    WebSocketVersion requestedVersion = WebSocketServerDuplexerHelper.detectVersion(request);
                    if (requestedVersion != null) {
                        consumed = true;
                        handleUpgrade(context, state, request, requestedVersion, dst);
                        continue;
                    }
                }

                if (!state.ready && handleStagedUpgrade(context, state, msg, dst)) {
                    continue;
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
            if (!state.ready && isWebSocketTraffic(msg)) {
                throw new WebSocketProtocolViolationException("websocket handshake is not complete; outbound WebSocket traffic is not allowed yet.");
            }
            dst.offerMessage(msg);
        }
        return ProtoStatus.Next;
    }

    private boolean handleStagedUpgrade(ProtoContext context, HandshakeState state, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        if (state.collector.isActive() || msg instanceof HttpRequest) {
            state.bufferedMessages.add(msg);
            state.collector.append(msg);

            if (state.pendingVersion == null && state.collector.isHeadersClosed()) {
                FullHttpRequest snapshot = state.collector.snapshot();
                state.pendingVersion = snapshot != null ? WebSocketServerDuplexerHelper.detectVersion(snapshot) : null;
                if (snapshot != null) {
                    snapshot.release();
                }
                if (state.pendingVersion == null) {
                    flushBuffered(state, dst);
                    resetHandshakeState(state, false);
                    return true;
                }
            }

            if (state.pendingVersion != null && state.collector.isComplete()) {
                FullHttpRequest request = state.collector.snapshot();
                try {
                    handleUpgrade(context, state, request, state.pendingVersion, dst);
                } finally {
                    request.release();
                    resetHandshakeState(state, true);
                }
                return true;
            }
            return true;
        }
        return false;
    }

    private void handleUpgrade(ProtoContext context, HandshakeState state, FullHttpRequest request, WebSocketVersion requestedVersion, ProtoSndQueue<HttpObject> dst) throws Throwable {
        if (!isCompatible(requestedVersion)) {
            throw new WebSocketProtocolViolationException("websocket handshake version " + requestedVersion + " is incompatible with frame codec version " + this.codecVersion + ".");
        }

        FullHttpResponse handshakeResponse = WebSocketServerDuplexerHelper.handshakeResponse(request);
        context.sendData(handshakeResponse);

        ((NetChannel) context.getChannel()).fireUserEvent(HttpThroughEvent.class, HttpThroughEvent.enable());
        context.context(WebSocketContext.class, WebSocketContextImpl.fromHandshake(requestedVersion, request.uri(), request.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL), request.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS)));

        state.ready = true;
        dst.offerMessage(HandshakeWebSocketMessage.from(requestedVersion, request));
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

    private void flushBuffered(HandshakeState state, ProtoSndQueue<HttpObject> dst) {
        for (HttpObject buffered : state.bufferedMessages) {
            dst.offerMessage(buffered);
        }
        state.bufferedMessages.clear();
    }

    private void resetHandshakeState(HandshakeState state, boolean releaseBuffered) {
        if (releaseBuffered) {
            for (HttpObject buffered : state.bufferedMessages) {
                buffered.release();
            }
        }
        state.bufferedMessages.clear();
        state.collector.reset();
        state.pendingVersion = null;
    }

    @Override
    public void onClose(ProtoContext context) {
        resetHandshakeState(state(context), true);
    }
}