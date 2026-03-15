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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

class WebSocketHandshake4Server extends AbstractWebSocketHandshake {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandshake4Server.class);

    // Handshake state is intentionally split into three layers:
    // 1) authorization pending state: authPending/authAttemptId
    // 2) current handshake snapshot: request-derived metadata used to build 101/reject responses
    // 3) full handshake session state: requestParts + ready + the two layers above
    private static final class ServerHandshakeState {
        private final HttpMessageParts        requestParts = new HttpMessageParts();
        private       boolean                 ready;
        private       boolean                 authPending;
        private       long                    authAttemptId;
        private       long                    attemptSeq;
        private       HttpVersion             httpVersion;
        private       HttpMethod              method;
        private       int                     streamId;
        private       WebSocketVersion        version;
        private       String                  path;
        private       String                  host;
        private       String                  origin;
        private       String                  protocols;
        private       String                  extensions;
        private       String                  key;
        private       String                  key1;
        private       String                  key2;
        private       byte[]                  key3;
        private       WebSocketHandshakeEvent handshakeEvent;
    }

    private final WebSocketHandshakeAuthorizer authorizer;

    public WebSocketHandshake4Server(WebSocketVersion codecVersion) {
        this(codecVersion, (event, c) -> c.accept());
    }

    public WebSocketHandshake4Server(WebSocketVersion codecVersion, WebSocketHandshakeAuthorizer authorizer) {
        super(codecVersion);
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer is null");
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        state(context);
    }

    @Override
    protected void resetState(ProtoContext context) {
        resetHandshakeSession(state(context));
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,           //
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, //
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleReceive(context, rcvUp, rcvDown);
        } else {
            return this.handleSend(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        ServerHandshakeState state = state(context);
        WebSocketHandshakeException handshakeError = this.handshakeError(e);
        if (!state.ready && handshakeError != null) {
            if (handshakeError.status().code() >= 500) {
                logger.error("Unhandled websocket server handshake internal error.", e);
            } else {
                logger.warn("Unhandled websocket server handshake failure: " + handshakeError.getMessage());
            }

            try {
                this.rejectHandshake(context, state, handshakeError.status().code(), handshakeError.getMessage(), handshakeError.headers(), handshakeError.body());
            } catch (Exception sendError) {
                logger.error("Failed to send websocket handshake failure response.", sendError);
            }

            this.resetHandshakeSession(state);
            eh.clear();
            if (handshakeError.closeConnection()) {
                context.getChannel().close();
                return ProtoStatus.Stop;
            }
            return ProtoStatus.Next;
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus handleSend(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        ServerHandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            if (state.ready) {
                dst.offerMessage(msg);
                continue;
            }

            if (state.authPending) {
                this.warnDropReason(context, "server-snd", "drop outbound data while handshake authorization is pending.");
                msg.release();
                continue;
            }

            if (!isHttpResponsePart(msg)) {
                this.warnAndDrop(context, "server-snd", msg);
                msg.release();
                continue;
            }

            dst.offerMessage(msg);
        }
        return ProtoStatus.Next;
    }

    //
    // handleReceive
    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        ServerHandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            if (state.ready) {
                dst.offerMessage(msg);
                continue;
            }

            // ERROR1: someone rushing ahead during the handshake.
            if (state.authPending) {
                String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", Authorization is pending.";
                this.warnDropReason(context, "server-rcv", "drop inbound data while handshake, " + error);
                this.endAuthorizationPending(state);
                msg.release();
                throw this.handshakeFailure(HttpStatus.BAD_REQUEST, error, false);
            }

            // ERROR2: before 101 response is sent, only HTTP request fragments are valid handshake input.
            if (!isHttpRequestPart(msg)) {
                String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", HTTP request part required.";
                this.warnAndDrop(context, "server-rcv", msg);
                msg.release();
                throw this.handshakeFailure(HttpStatus.BAD_REQUEST, error, false);
            }

            boolean complete = false;
            try {
                // start handshake
                if (msg instanceof HttpRequest) {
                    if (state.requestParts.isActive()) {
                        state.requestParts.reset();
                    }
                    if (state.ready) {
                        state.ready = false;
                    }
                }

                // collect data.
                state.requestParts.appendRequest(msg);
                complete = state.requestParts.isComplete();
                if (!complete) {
                    continue;
                }

                // ERROR3: payload (RFC6455 no content, V0 allow 8 bytes)
                String payloadError = verifyPayload(state, state.requestParts);
                if (payloadError != null) {
                    String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", Invalid Payload.";
                    this.warnDropReason(context, "server-rcv", error);
                    throw this.handshakeFailure(HttpStatus.BAD_REQUEST, error, false);
                }

                // ERROR4: bad handshake data
                if (!tryHandshake(state, state.requestParts)) {
                    String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", Invalid Handshake Data.";
                    this.warnDropReason(context, "server-rcv", error);
                    throw this.handshakeFailure(HttpStatus.BAD_REQUEST, error, false);
                }

                // ERROR5: incompatible versions
                if (!isCompatible(state.version)) {
                    DefaultHttpHeaders headers = new DefaultHttpHeaders();
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(this.codecVersion.code()));

                    String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", Unsupported version: " + state.version;
                    this.warnDropReason(context, "server-rcv", error);
                    throw this.handshakeFailure(HttpStatus.UPGRADE_REQUIRED, error, headers, null, false);
                }

                // authorize handshake
                long attemptId = ++state.attemptSeq;
                state.authPending = true;
                state.authAttemptId = attemptId;
                AuthorizationCallback callback = new AuthorizationCallback(context, attemptId);
                try {
                    this.authorizer.authorize(state.handshakeEvent, callback);
                } catch (Throwable e) {
                    logger.error("Error occurred while authorizing websocket handshake.", e);
                    callback.reject(HttpStatus.INTERNAL_SERVER_ERROR);
                    SoContextService soContext = (SoContextService) context.getSoContext();
                    soContext.notifyRcvChannelException(context.getChannel().getChannelId(), false, new SoException("websocket handshake authorizer failed", e));
                    context.getChannel().close();
                } finally {
                    callback.exitAuthorize();
                }

                if (state.authPending) {
                    return ProtoStatus.Stop;
                }
            } finally {
                msg.release();
                if (complete) {
                    state.requestParts.reset();
                    if (!state.authPending) {
                        discardHandshakeSnapshot(state);
                    }
                }
            }
        }

        return ProtoStatus.Next;
    }

    private WebSocketHandshakeException handshakeFailure(HttpStatus status, String message, boolean closeConnection) {
        return new WebSocketHandshakeException(status, message, closeConnection);
    }

    private WebSocketHandshakeException handshakeFailure(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection) {
        return new WebSocketHandshakeException(status, message, headers, body, closeConnection);
    }

    private String verifyPayload(ServerHandshakeState state, HttpMessageParts request) {
        WebSocketVersion version = detectVersion(state.requestParts);
        if (request == null || version == null) {
            return null;
        }

        state.httpVersion = request.protocolVersion();
        state.streamId = request.streamId();

        int bodyLength = request.body().readableBytes();
        if (version.isRfc6455Framing()) {
            if (bodyLength > 0) {
                return "RFC6455 websocket handshake must not include request body";
            }
            return null;
        }

        if (bodyLength != 8) {
            return "legacy websocket handshake body must be exactly 8 bytes";
        }
        return null;
    }

    private boolean tryHandshake(ServerHandshakeState state, HttpMessageParts request) {
        WebSocketVersion version = detectVersion(request);
        if (version == null) {
            return false;
        }

        String requestKey = null;
        String requestKey1 = null;
        String requestKey2 = null;
        byte[] requestKey3 = null;
        if (version.isRfc6455Framing()) {
            requestKey = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            if (requestKey == null || requestKey.trim().isEmpty()) {
                return false;
            }
            requestKey = requestKey.trim();
        } else {
            requestKey1 = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
            requestKey2 = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
            if (requestKey1 == null || requestKey1.trim().isEmpty() || requestKey2 == null || requestKey2.trim().isEmpty()) {
                return false;
            }
            ByteBuf body = request.body();
            if (body == null || body.readableBytes() != 8) {
                return false;
            }
            requestKey3 = new byte[8];
            body.getBytes(0, requestKey3, 0, 8);
        }

        state.httpVersion = request.protocolVersion();
        state.method = request.method();
        state.streamId = request.streamId();
        state.version = version;
        state.path = request.uri();
        state.host = request.header(HttpHeaderNames.HOST);
        state.origin = request.header("origin");
        state.protocols = request.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        state.extensions = request.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        state.key = requestKey;
        state.key1 = requestKey1;
        state.key2 = requestKey2;
        state.key3 = requestKey3;
        WebSocketHandshakeEvent handshakeEvent = new WebSocketHandshakeEvent(state.version, state.path, state.protocols, state.extensions, request.headersSnapshot());
        handshakeEvent.streamId(state.streamId);
        state.handshakeEvent = handshakeEvent;
        return true;
    }

    //
    // authorization
    private final class AuthorizationCallback implements WebSocketHandshakeCallback {
        private final    ProtoContext  context;
        private final    long          attemptId;
        private final    AtomicBoolean completed;
        private volatile boolean       inAuthorize;

        private AuthorizationCallback(ProtoContext context, long attemptId) {
            this.context = context;
            this.attemptId = attemptId;
            this.completed = new AtomicBoolean(false);
            this.inAuthorize = true;
        }

        private void exitAuthorize() {
            this.inAuthorize = false;
        }

        @Override
        public void accept() {
            this.accept(DefaultLastHttpHeaders.EMPTY);
        }

        @Override
        public void accept(HttpHeaders headers) {
            this.resolve(true, HttpStatus.SWITCHING_PROTOCOLS.code(), HttpStatus.SWITCHING_PROTOCOLS.reasonPhrase(), headers, null);
        }

        @Override
        public void reject() {
            this.reject(HttpStatus.METHOD_NOT_ALLOWED.code(), HttpStatus.METHOD_NOT_ALLOWED.reasonPhrase(), DefaultLastHttpHeaders.EMPTY, null);
        }

        @Override
        public void reject(HttpStatus status) {
            Objects.requireNonNull(status, "status is null");
            this.reject(status.code(), status.reasonPhrase(), DefaultLastHttpHeaders.EMPTY, null);
        }

        @Override
        public void reject(HttpStatus status, byte[] body) {
            Objects.requireNonNull(status, "status is null");
            this.reject(status.code(), status.reasonPhrase(), DefaultLastHttpHeaders.EMPTY, body);
        }

        @Override
        public void reject(HttpStatus status, HttpHeaders headers, byte[] body) {
            Objects.requireNonNull(status, "status is null");
            headers = headers == null ? DefaultLastHttpHeaders.EMPTY : headers;
            this.reject(status.code(), status.reasonPhrase(), headers, body);
        }

        @Override
        public void reject(int code, String reasonPhrase) {
            this.reject(code, reasonPhrase, DefaultLastHttpHeaders.EMPTY, null);
        }

        @Override
        public void reject(int code, String reasonPhrase, byte[] body) {
            this.reject(code, reasonPhrase, DefaultLastHttpHeaders.EMPTY, body);
        }

        //

        @Override
        public void reject(int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
            if (code < 400) {
                throw new IllegalArgumentException("reject status code must be greater than or equal to 400.");
            }

            if (StringUtils.isBlank(reasonPhrase)) {
                reasonPhrase = "Unknown";
            }

            this.resolve(false, code, reasonPhrase, headers, body);
        }

        private void resolve(boolean allow, int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
            if (!this.completed.compareAndSet(false, true)) {
                return;
            }

            Runnable r = () -> safeFinishAuthorization(//
                    this.context, this.attemptId, //
                    allow, code, reasonPhrase, headers, body);

            if (this.inAuthorize) {
                r.run();
            } else {
                SoContextService c = (SoContextService) this.context.getSoContext();
                c.submitSoTask(new SimpleTask(r), null);
            }
        }
    }

    private void safeFinishAuthorization(ProtoContext context, long attemptId, //
            boolean allow, int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
        try {
            finishAuthorization(context, state(context), attemptId, allow, code, reasonPhrase, headers, body);
        } catch (Throwable e) {
            logger.error("Error occurred while completing websocket handshake authorization.", e);
            resetHandshakeSession(state(context));
            context.getChannel().close();
        }
    }

    private void finishAuthorization(ProtoContext context, ServerHandshakeState state, //
            long attemptId, boolean allow, int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
        if (!state.authPending || state.authAttemptId != attemptId) {
            return;
        }

        this.endAuthorizationPending(state);

        if (!allow) {
            this.rejectHandshake(context, state, code, reasonPhrase, headers, body);
            this.resetHandshakeSession(state);
            return;
        }

        try {
            DefaultLastHttpHeaders responseHeaders = this.finishHandshake(context, state, headers);
            String negotiatedProtocol = responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            String negotiatedExtensions = responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
            context.context(WebSocketContext.class, WebSocketContextImpl.fromHandshake(state.version, state.path, negotiatedProtocol, negotiatedExtensions));
            context.fireUserEventSnd(HttpThroughEvent.class, HttpThroughEvent.enable());
            state.ready = true;
        } catch (Throwable e) {
            logger.error("Error occurred while finalizing websocket handshake protocol state.", e);
            this.resetHandshakeSession(state);
            context.getChannel().close();
            return;
        }

        try {
            context.fireUserEventRcv(WebSocketHandshakeEvent.class, state.handshakeEvent);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing websocket handshake event.", e);
        }

        discardHandshakeSnapshot(state);
    }

    private DefaultLastHttpHeaders finishHandshake(ProtoContext context, ServerHandshakeState state, HttpHeaders acceptHeaders) {
        DefaultHttpResponse responseLine = new DefaultHttpResponse(responseVersion(state), HttpStatus.SWITCHING_PROTOCOLS);
        responseLine.streamId(state.streamId);

        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
        headers.streamId(state.streamId);

        HttpObject lastContent;
        if (state.version.isRfc6455Framing()) {
            headers.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            headers.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(state.key));
            if (StringUtils.isNotBlank(state.protocols)) {
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, state.protocols);
            }
            lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY).streamId(state.streamId);
        } else {
            headers.setHeader(HttpHeaderNames.UPGRADE, "WebSocket");
            headers.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            if (StringUtils.isNotBlank(state.origin)) {
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ORIGIN, state.origin);
            }
            if (StringUtils.isNotBlank(state.host)) {
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_LOCATION, "ws://" + state.host + (state.path != null ? state.path : "/"));
            }
            if (StringUtils.isNotBlank(state.protocols)) {
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, state.protocols);
            }

            byte[] challengeResponse = computeHixie76Response(state.key1, state.key2, state.key3);
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(challengeResponse.length, Integer.MAX_VALUE);
            body.writeBytes(challengeResponse, 0, challengeResponse.length);
            body.markWriter();
            lastContent = new DefaultLastHttpContent(body).streamId(state.streamId);
        }

        if (acceptHeaders != null) {
            headers.appendHeaders(acceptHeaders);
        }

        context.sendData(responseLine);
        context.sendData(headers);
        context.sendData(lastContent);
        return headers;
    }

    private void rejectHandshake(ProtoContext context, ServerHandshakeState state, int code, String reasonPhrase, HttpHeaders headers, byte[] bodyBytes) {
        ByteBuf body = ByteBuf.EMPTY;
        if (bodyBytes != null && bodyBytes.length > 0) {
            body = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length, Integer.MAX_VALUE);
            body.writeBytes(bodyBytes, 0, bodyBytes.length);
            body.markWriter();
        }

        DefaultHttpResponse responseLine = new DefaultHttpResponse(responseVersion(state).text(), String.valueOf(code), reasonPhrase);
        DefaultLastHttpHeaders responseHeaders = new DefaultLastHttpHeaders(headers);
        responseLine.streamId(state.streamId);
        responseHeaders.streamId(state.streamId);
        responseHeaders.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.readableBytes()));

        context.sendData(responseLine);
        context.sendData(responseHeaders);
        context.sendData(new DefaultLastHttpContent(body).streamId(state.streamId));
    }

    //
    // tools
    private ServerHandshakeState state(ProtoContext context) {
        ServerHandshakeState state = context.context(ServerHandshakeState.class);
        if (state == null) {
            state = new ServerHandshakeState();
            context.context(ServerHandshakeState.class, state);
        }
        return state;
    }

    private HttpVersion responseVersion(ServerHandshakeState state) {
        return state.httpVersion != null ? state.httpVersion : HttpVersion.HTTP_1_1;
    }

    // Authorization has completed for the current attempt, but the current handshake
    // snapshot must remain available until accept/reject finishing logic consumes it.
    private void endAuthorizationPending(ServerHandshakeState state) {
        state.authPending = false;
        state.authAttemptId = 0L;
    }

    // Discard only the current request snapshot. This must not touch requestParts,
    // ready, or the authorization-pending markers because those belong to broader
    // handshake session state transitions.
    private void discardHandshakeSnapshot(ServerHandshakeState state) {
        state.httpVersion = null;
        state.method = null;
        state.streamId = 0;
        state.version = null;
        state.path = null;
        state.host = null;
        state.origin = null;
        state.protocols = null;
        state.extensions = null;
        state.key = null;
        state.key1 = null;
        state.key2 = null;
        state.key3 = null;
        state.handshakeEvent = null;
    }

    // Reset the whole opening-handshake session so the connection can either retry
    // a new handshake or fully leave handshake mode after a fatal path.
    private void resetHandshakeSession(ServerHandshakeState state) {
        discardHandshakeSnapshot(state);
        state.requestParts.reset();
        endAuthorizationPending(state);
        state.ready = false;
    }
}