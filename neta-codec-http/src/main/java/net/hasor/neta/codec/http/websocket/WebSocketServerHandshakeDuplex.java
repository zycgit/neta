/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvData;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.codec.http.*;
/**
 * Server opening-handshake duplexer for WebSocket upgrades.
 * <p>
 * It aggregates the inbound HTTP upgrade request, delegates the authorization
 * decision, emits the HTTP switching-protocols response, and installs the
 * negotiated {@link WebSocketContext} when the handshake succeeds.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketServerHandshakeDuplex extends AbstractWebSocketHandshake {
    private static final Logger                logger          = LoggerFactory.getLogger(WebSocketServerHandshakeDuplex.class);
    private static final String                STATE_STORE_KEY = WebSocketServerHandshakeDuplex.class.getName() + ".stateStore";
    private final WebSocketSettings            settings;
    private final WebSocketHandshakeAuthorizer authorizer;
    private final boolean                      skipHandshakeRequestSnapshot;

    private static final class ServerHandshakeState {
        private final HttpMessageParts    requestParts = new HttpMessageParts();
        private boolean                   ready;
        private boolean                   authPending;
        private long                      authAttemptId;
        private long                      attemptSeq;
        private HttpVersion               httpVersion;
        private HttpMethod                method;
        private long                      streamId;
        private boolean                   standardHttp2;
        private WebSocketVersion          version;
        private String                    path;
        private String                    host;
        private String                    origin;
        private String                    protocols;
        private String                    extensions;
        private String                    key;
        private String                    key1;
        private String                    key2;
        private byte[]                    key3;
        private WebSocketHandshakeRequest handshakeRequest;
    }

    private static final class ServerHandshakeStateStore {
        private final ConcurrentHashMap<PartitionKey, ServerHandshakeState> states = new ConcurrentHashMap<>();
    }

    /**
     * Create a server handshake duplexer for the given websocket version.
     * @param codecVersion websocket version to negotiate
     */
    public WebSocketServerHandshakeDuplex(WebSocketVersion codecVersion) {
        this(WebSocketSettings.defaultSettings(codecVersion));
    }

    /**
     * Create a server handshake duplexer with an explicit authorization hook.
     * @param codecVersion websocket version to negotiate
     * @param authorizer callback that accepts or rejects the request
     */
    public WebSocketServerHandshakeDuplex(WebSocketVersion codecVersion, WebSocketHandshakeAuthorizer authorizer) {
        this(WebSocketSettings.of(codecVersion).handshakeAuthorizer(authorizer));
    }

    /**
     * Create a server handshake duplexer from the full websocket settings.
     * @param settings websocket handshake settings
     */
    public WebSocketServerHandshakeDuplex(WebSocketSettings settings) {
        super(Objects.requireNonNull(settings, "settings is null").version());
        this.settings = settings;
        this.authorizer = Objects.requireNonNull(settings.handshakeAuthorizer(), "handshakeAuthorizer is null");
        this.skipHandshakeRequestSnapshot = WebSocketSettings.isDefaultHandshakeAuthorizer(this.authorizer);
    }

    /**
     * Initialize the per-channel server handshake state container.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        state(context);
    }

    /**
     * Reset the entire server-side handshake session state.
     */
    @Override
    protected void resetState(ProtoContext context) {
        resetHandshakeSession(state(context));
    }

    boolean isHandshakeReady(ProtoContext context) {
        return state(context).ready;
    }

    /**
     * Route inbound and outbound HTTP objects through the server handshake flow.
     */
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

    ProtoStatus onReceiveData(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        return this.handleReceive(context, src, dst);
    }

    ProtoStatus onSendData(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        return this.handleSend(context, src, dst);
    }

    ProtoStatus onReceiveMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        return this.handleReceiveMessage(context, msg, dst);
    }

    /**
     * Convert handshake failures into HTTP rejection responses when possible.
     */
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
                this.rejectHandshake(context, state, handshakeError.status().code(), handshakeError.getMessage(), handshakeError.headers(), handshakeError.body(), handshakeError.closeConnection());
            } catch (Exception sendError) {
                logger.error("Failed to send websocket handshake failure response.", sendError);
                InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
            }

            this.resetHandshakeSession(state);
            eh.clear();
            if (handshakeError.closeConnection()) {
                return ProtoStatus.Stop;
            } else {
                return ProtoStatus.Next;
            }
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus handleSend(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        while (src.hasMore()) {
            // handleSendMessage consumes at most one downstream slot on forwarding paths.
            if (!dst.hasSlot()) {
                return ProtoStatus.Stop;
            }

            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            ProtoStatus status = this.handleSendMessage(context, msg, dst);
            if (status != ProtoStatus.Next) {
                return status;
            }
        }
        return ProtoStatus.Next;
    }

    private ProtoStatus handleSendMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        ServerHandshakeState state = state(context, msg);
        if (state.ready) {
            dst.offerMessage(msg);
            return ProtoStatus.Next;
        }

        if (state.authPending) {
            this.warnDropReason(context, "server-snd", "drop outbound data while handshake authorization is pending.");
            msg.release();
            return ProtoStatus.Next;
        }

        if (!isHttpResponsePart(msg)) {
            this.warnAndDrop(context, "server-snd", msg);
            msg.release();
            return ProtoStatus.Next;
        }

        dst.offerMessage(msg);
        return ProtoStatus.Next;
    }

    //
    // handleReceive
    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            // Do not take from src until dst can accept one message.
            if (!dst.hasSlot()) {
                return ProtoStatus.Stop;
            }

            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            ProtoStatus status = this.handleReceiveMessage(context, msg, dst);
            if (status != ProtoStatus.Next) {
                return status;
            }
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus handleReceiveMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        ServerHandshakeState state = state(context, msg);
        if (state.ready) {
            dst.offerMessage(msg);
            return ProtoStatus.Next;
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
            state.requestParts.appendOwnedRequest(msg);
            complete = state.requestParts.isComplete();
            if (!complete && this.isHttp2HeaderOnlyHandshakeBoundary(msg, state.requestParts)) {
                complete = true;
            }
            if (!complete) {
                return ProtoStatus.Next;
            }

            // ERROR3: payload (RFC6455 no content, V0 allow 8 bytes)
            boolean standardHttp2 = InternalUtils.isStandardHttp2WebSocketRequest(state.requestParts);
            WebSocketVersion version = detectVersion(state.requestParts, standardHttp2);

            String payloadError = verifyPayload(state, state.requestParts, version, standardHttp2);
            if (payloadError != null) {
                String error = HttpStatus.BAD_REQUEST.reasonPhrase() + ", Invalid Payload.";
                this.warnDropReason(context, "server-rcv", error);
                throw this.handshakeFailure(HttpStatus.BAD_REQUEST, error, false);
            }

            // ERROR4: bad handshake data
            if (!tryHandshake(state, state.requestParts, version, standardHttp2)) {
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
            if (this.skipHandshakeRequestSnapshot) {
                HttpStatus successStatus = state.standardHttp2 ? HttpStatus.OK : HttpStatus.SWITCHING_PROTOCOLS;
                this.safeFinishAuthorization(context, state, attemptId, true, successStatus.code(), successStatus.reasonPhrase(), DefaultLastHttpHeaders.EMPTY, null);
            } else {
                AuthorizationCallback callback = new AuthorizationCallback(context, state, attemptId);
                try {
                    this.authorizer.authorize(state.handshakeRequest, callback);
                } catch (Throwable e) {
                    logger.error("Error occurred while authorizing websocket handshake.", e);
                    callback.reject(HttpStatus.INTERNAL_SERVER_ERROR);
                    SoContextService soContext = (SoContextService) context.getSoContext();
                    soContext.notifyRcvChannelException(context.getChannel().getChannelId(), false, new SoException("websocket handshake authorizer failed", e));
                    InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                } finally {
                    callback.exitAuthorize();
                }
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

        return ProtoStatus.Next;
    }

    private WebSocketHandshakeException handshakeFailure(HttpStatus status, String message, boolean closeConnection) {
        return new WebSocketHandshakeException(status, message, closeConnection);
    }

    private WebSocketHandshakeException handshakeFailure(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection) {
        return new WebSocketHandshakeException(status, message, headers, body, closeConnection);
    }

    private boolean isHttp2HeaderOnlyHandshakeBoundary(HttpObject msg, HttpMessageParts requestParts) {
        if (!(msg instanceof LastHttpHeaders) || requestParts == null || requestParts.protocolVersion() == null) {
            return false;
        }
        if (requestParts.protocolVersion().majorVersion() != 2) {
            return false;
        }
        if (requestParts.body() != null && requestParts.body().readableBytes() > 0) {
            return false;
        }
        if (InternalUtils.isStandardHttp2WebSocketRequest(requestParts)) {
            return true;
        }
        String upgrade = requestParts.header(HttpHeaderNames.UPGRADE);
        String connection = requestParts.header(HttpHeaderNames.CONNECTION);
        return StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && containsHeaderValueIgnoreCase(connection, HttpHeaderValues.UPGRADE);
    }

    private String verifyPayload(ServerHandshakeState state, HttpMessageParts request, WebSocketVersion version, boolean standardHttp2) {
        if (request == null || version == null) {
            return null;
        }

        state.httpVersion = request.protocolVersion();
        state.streamId = request.streamId();
        state.standardHttp2 = standardHttp2;

        ByteBuf body = request.body();
        int bodyLength = body != null ? body.readableBytes() : 0;
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

    private boolean tryHandshake(ServerHandshakeState state, HttpMessageParts request, WebSocketVersion version, boolean standardHttp2) {
        if (version == null) {
            return false;
        }

        String requestKey = null;
        String requestKey1 = null;
        String requestKey2 = null;
        byte[] requestKey3 = null;
        if (version.isRfc6455Framing()) {
            if (!standardHttp2) {
                requestKey = normalizeHeaderValue(request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY));
                if (requestKey == null) {
                    return false;
                }
            }
        } else {
            requestKey1 = normalizeHeaderValue(request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY1));
            requestKey2 = normalizeHeaderValue(request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY2));
            if (requestKey1 == null || requestKey2 == null) {
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
        state.standardHttp2 = standardHttp2;
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

        if (!this.skipHandshakeRequestSnapshot) {
            WebSocketHandshakeRequest handshakeRequest = WebSocketHandshakeRequest.fromOwnedHeaders(state.version, state.path, state.protocols, state.extensions, request.headersView());
            handshakeRequest.streamId(state.streamId);
            state.handshakeRequest = handshakeRequest;
        }
        return true;
    }

    //
    // authorization
    private final class AuthorizationCallback implements WebSocketHandshakeCallback {
        private final ProtoContext         context;
        private final ServerHandshakeState state;
        private final long                 attemptId;
        private final AtomicBoolean        completed;
        private volatile boolean           inAuthorize;

        private AuthorizationCallback(ProtoContext context, ServerHandshakeState state, long attemptId) {
            this.context = context;
            this.state = state;
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
            HttpStatus successStatus = this.state.standardHttp2 ? HttpStatus.OK : HttpStatus.SWITCHING_PROTOCOLS;
            this.resolve(true, successStatus.code(), successStatus.reasonPhrase(), headers, null);
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
                    this.context, this.state, this.attemptId, //
                    allow, code, reasonPhrase, headers, body);

            if (this.inAuthorize) {
                r.run();
            } else {
                SoContextService c = (SoContextService) this.context.getSoContext();
                c.submitSoTask(new SimpleTask(r), null);
            }
        }
    }

    private void safeFinishAuthorization(ProtoContext context, ServerHandshakeState state, long attemptId, //
            boolean allow, int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
        try {
            finishAuthorization(context, state, attemptId, allow, code, reasonPhrase, headers, body);
        } catch (Throwable e) {
            logger.error("Error occurred while completing websocket handshake authorization.", e);
            resetHandshakeSession(state);
            InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
        }
    }

    private void finishAuthorization(ProtoContext context, ServerHandshakeState state, //
            long attemptId, boolean allow, int code, String reasonPhrase, HttpHeaders headers, byte[] body) {
        if (!state.authPending || state.authAttemptId != attemptId) {
            return;
        }

        this.endAuthorizationPending(state);

        if (!allow) {
            this.rejectHandshake(context, state, code, reasonPhrase, headers, body, false);
            this.resetHandshakeSession(state);
            return;
        }

        try {
            this.finishHandshake(context, state, headers);
            String negotiatedProtocol = state.protocols;
            String negotiatedExtensions = state.extensions;
            int versionCode = state.version != null ? state.version.code() : 13;
            List<WebSocketExtensionResult> extensionResults = InternalUtils.parseExtensions(negotiatedExtensions);
            List<WebSocketExtensionRuntime> runtimeExtensions = extensionResults.isEmpty() ? Collections.emptyList() : InternalUtils.resolveRuntimeExtensions(extensionResults, this.settings);
            WebSocketContextImpl socketContext = new WebSocketContextImpl(true, negotiatedProtocol, versionCode, state.path, state.host, state.origin, extensionResults, runtimeExtensions);

            this.finishWebSocketUpgrade(context, socketContext, state.streamId);
            state.ready = true;
        } catch (WebSocketHandshakeException e) {
            logger.warn("Websocket server handshake protocol violation: " + e.getMessage());
            this.rejectHandshake(context, state, e.status().code(), e.getMessage(), e.headers(), e.body(), false);
            this.resetHandshakeSession(state);
            return;
        } catch (Throwable e) {
            logger.error("Error occurred while finalizing websocket handshake protocol state.", e);
            this.resetHandshakeSession(state);
            InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
            return;
        }

        discardHandshakeSnapshot(state);
    }

    private void finishHandshake(ProtoContext context, ServerHandshakeState state, HttpHeaders acceptHeaders) {
        HttpStatus successStatus = state.standardHttp2 ? HttpStatus.OK : HttpStatus.SWITCHING_PROTOCOLS;
        DefaultHttpResponse responseLine = new DefaultHttpResponse(responseVersion(state), successStatus);
        responseLine.streamId(state.streamId);

        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
        headers.streamId(state.streamId);

        HttpObject lastContent = null;
        if (state.standardHttp2) {
        } else if (state.version.isRfc6455Framing()) {
            headers.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            headers.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(state.key));
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

            byte[] challengeResponse = computeHixie76Response(state.key1, state.key2, state.key3);
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(challengeResponse.length, Integer.MAX_VALUE);
            body.writeBytes(challengeResponse, 0, challengeResponse.length);
            body.markWriter();
            lastContent = new DefaultLastHttpContent(body).streamId(state.streamId);
        }

        if (acceptHeaders != null) {
            headers.appendHeaders(acceptHeaders);
        }

        validateNegotiatedHeaders(state, headers);

        if (lastContent != null) {
            context.sendEncoded(new Object[] { responseLine, headers, lastContent });
        } else {
            context.sendEncoded(new Object[] { responseLine, headers });
        }
    }

    private void validateNegotiatedHeaders(ServerHandshakeState state, HttpHeaders headers) {
        String selectedProtocol = headers.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        List<String> selectedProtocols = parseHeaderValues(selectedProtocol);
        if (selectedProtocols.size() > 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected more than one websocket sub-protocol.");
        }
        if (!selectedProtocols.isEmpty()) {
            List<String> requestedProtocols = parseHeaderValues(state.protocols);
            if (requestedProtocols.isEmpty() || !requestedProtocols.contains(selectedProtocols.get(0))) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsupported websocket sub-protocol.");
            }
        }

        String negotiatedExtensions = headers.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        String resolvedExtensions = this.resolveNegotiatedExtensions(state, negotiatedExtensions);

        if (StringUtils.isBlank(resolvedExtensions)) {
            if (negotiatedExtensions != null) {
                headers.removeHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
            }
        } else {
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, resolvedExtensions);
        }
        state.protocols = selectedProtocol;
        state.extensions = resolvedExtensions;
    }

    private String resolveNegotiatedExtensions(ServerHandshakeState state, String negotiatedExtensions) {
        if (StringUtils.isBlank(negotiatedExtensions)) {
            return null;
        }

        List<WebSocketExtension> supports = this.settings.extensionSupports();
        if (supports.isEmpty()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: negotiated extensions are disabled by current settings.");
        }

        WebSocketHandshakeRequest request = state.handshakeRequest;
        if (request == null) {
            throw new IllegalStateException("handshakeRequest is null");
        }

        List<WebSocketExtensionResult> requested = InternalUtils.parseExtensions(request.requestedExtensions());
        List<WebSocketExtensionResult> negotiated = InternalUtils.parseExtensions(negotiatedExtensions);
        this.ensureNoDuplicateExtensions(negotiated, "websocket handshake failed: duplicated negotiated websocket extension: ");

        List<String> resolved = new ArrayList<>(negotiated.size());
        for (WebSocketExtensionResult negotiatedItem : negotiated) {
            WebSocketExtension support = this.findExtensionSupport(supports, negotiatedItem.name());
            if (support == null) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: unsupported negotiated websocket extension: " + negotiatedItem.name());
            }

            String requestedHeader = this.findSingleExtensionHeaderValue(requested, negotiatedItem.name(), "websocket handshake failed: duplicated requested websocket extension: " + negotiatedItem.name());
            WebSocketHandshakeRequest scopedRequest = this.copyHandshakeRequest(request, requestedHeader);
            String resolvedHeader = support.selectServerExtensions(scopedRequest, negotiatedItem.asHeaderValue());
            if (StringUtils.isNotBlank(resolvedHeader)) {
                resolved.add(resolvedHeader);
            }
        }

        return this.joinHeaderValues(resolved);
    }

    private void rejectHandshake(ProtoContext context, ServerHandshakeState state, int code, String reasonPhrase, HttpHeaders headers, byte[] bodyBytes, boolean closeAfterSend) {
        ByteBuf body = ByteBuf.EMPTY;
        if (bodyBytes != null && bodyBytes.length > 0) {
            body = ByteBufAllocator.DEFAULT.buffer(bodyBytes.length, Integer.MAX_VALUE);
            body.writeBytes(bodyBytes, 0, bodyBytes.length);
            body.markWriter();
        }

        DefaultHttpResponse responseLine = new DefaultHttpResponse(responseVersion(state), HttpStatus.valueOf(code, reasonPhrase == null ? "" : reasonPhrase));
        DefaultLastHttpHeaders responseHeaders = new DefaultLastHttpHeaders(headers);
        responseLine.streamId(state.streamId);
        responseHeaders.streamId(state.streamId);
        responseHeaders.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.readableBytes()));

        context.sendData(responseLine);
        context.sendData(responseHeaders);
        if (closeAfterSend) {
            InternalUtils.executeCloseAction(context, WebSocketCloseType.SEND_CLOSE_AND_TERMINATE, context.sendData(new DefaultLastHttpContent(body).streamId(state.streamId)));
        } else {
            context.sendData(new DefaultLastHttpContent(body).streamId(state.streamId));
        }
    }

    //
    // tools
    private ServerHandshakeState state(ProtoContext context) {
        ServerHandshakeState state = context.context(ServerHandshakeState.class);
        return state != null ? state : state(context, null);
    }

    private ServerHandshakeState state(ProtoContext context, HttpObject msg) {
        if (!useStateStore(context, msg)) {
            ServerHandshakeState localState = context.context(ServerHandshakeState.class);
            if (localState == null) {
                localState = new ServerHandshakeState();
                context.context(ServerHandshakeState.class, localState);
            }
            return localState;
        }

        ServerHandshakeStateStore store = stateStore(context);

        PartitionKey key = partitionKey(context, msg);
        ServerHandshakeState state = store.states.get(key);
        if (state == null) {
            ServerHandshakeState newState = new ServerHandshakeState();
            ServerHandshakeState oldState = store.states.putIfAbsent(key, newState);
            state = oldState != null ? oldState : newState;
        }
        context.context(ServerHandshakeState.class, state);
        return state;
    }

    private ServerHandshakeStateStore stateStore(ProtoContext context) {
        SoChannel<?> channel = context.getChannel();
        Object attr = channel != null ? channel.getAttribute(STATE_STORE_KEY) : null;
        if (attr instanceof ServerHandshakeStateStore) {
            return (ServerHandshakeStateStore) attr;
        }

        ServerHandshakeStateStore store = new ServerHandshakeStateStore();
        if (channel != null) {
            channel.setAttribute(STATE_STORE_KEY, store);
        }
        return store;
    }

    private static PartitionKey partitionKey(ProtoContext context, HttpObject msg) {
        if (msg != null && msg.streamId() > 0 && isHttp2StreamMessage(context, msg)) {
            return PartitionKey.newKey(msg.streamId());
        }
        PartitionKey key = PartitionKey.findKey(context);
        if (key != null && !PartitionKey.defaultKey().equals(key)) {
            return key;
        }
        return key != null ? key : PartitionKey.defaultKey();
    }

    private static boolean isHttp2StreamMessage(ProtoContext context, HttpObject msg) {
        if (InternalUtils.resolveHttpScope(context) == HttpScope.STREAM) {
            return true;
        }
        if (msg instanceof HttpRequest) {
            return ((HttpRequest) msg).protocolVersion().majorVersion() == 2;
        }
        if (msg instanceof HttpResponse) {
            return ((HttpResponse) msg).protocolVersion().majorVersion() == 2;
        }
        HttpVersion version = context.context(HttpVersion.class);
        return version != null && version.majorVersion() == 2;
    }

    private static boolean useStateStore(ProtoContext context, HttpObject msg) {
        if (msg != null && msg.streamId() > 0 && isHttp2StreamMessage(context, msg)) {
            return true;
        }
        PartitionKey key = PartitionKey.findKey(context);
        return key != null && !PartitionKey.defaultKey().equals(key);
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
        state.standardHttp2 = false;
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

        WebSocketHandshakeRequest handshakeRequest = state.handshakeRequest;
        state.handshakeRequest = null;
        if (handshakeRequest != null) {
            handshakeRequest.release();
        }
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
