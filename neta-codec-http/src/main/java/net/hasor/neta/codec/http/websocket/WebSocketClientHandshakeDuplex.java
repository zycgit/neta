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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.data.ProtoRcvData;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.data.ProtoSndQueueView;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.codec.http.*;
/**
 * Client opening-handshake duplexer for WebSocket upgrades.
 * <p>
 * It buffers the outbound HTTP upgrade request long enough to capture the
 * handshake parameters, validates the inbound HTTP 101 response, and publishes
 * the negotiated {@link WebSocketContext} when the upgrade completes.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketClientHandshakeDuplex extends AbstractWebSocketHandshake {
    private static final Logger     logger              = LoggerFactory.getLogger(WebSocketClientHandshakeDuplex.class);
    private static final String     STATE_STORE_KEY     = WebSocketClientHandshakeDuplex.class.getName() + ".stateStore";
    private static final String     BUFFER_QUEUE_PREFIX = WebSocketClientHandshakeDuplex.class.getName() + ".pendingRequest.";
    private final WebSocketSettings settings;

    private static final class ClientHandshakeState {
        private final HttpMessageParts        requestParts  = new HttpMessageParts();
        private final HttpMessageParts        responseParts = new HttpMessageParts();
        private ProtoSndQueueView<HttpObject> bufferedRequestViewRef;
        private boolean                       requestPending;
        private boolean                       ready;
        private long                          requestStreamId;
        private boolean                       standardHttp2;
        private WebSocketVersion              version;
        private String                        path;
        private String                        host;
        private String                        origin;
        private String                        protocols;
        private String                        extensions;
        private String                        key;
        private String                        key1;
        private String                        key2;
        private byte[]                        key3;
    }

    private static final class ClientHandshakeStateStore {
        private final ConcurrentHashMap<PartitionKey, ClientHandshakeState> states = new ConcurrentHashMap<>();
    }

    /**
     * Create a client handshake duplexer for the given protocol version.
     * @param codecVersion websocket version to negotiate
     */
    public WebSocketClientHandshakeDuplex(WebSocketVersion codecVersion) {
        this(WebSocketSettings.of(codecVersion));
    }

    /**
     * Create a client handshake duplexer with automatic handshake settings.
     * @param codecVersion websocket version to negotiate
     * @param autoHandshakeConfig auto-handshake request settings
     */
    public WebSocketClientHandshakeDuplex(WebSocketVersion codecVersion, WebSocketAutoHandshakeConfig autoHandshakeConfig) {
        this(WebSocketSettings.of(codecVersion).autoHandshakeConfig(autoHandshakeConfig));
    }

    /**
     * Create a client handshake duplexer from the full websocket settings.
     * @param settings websocket handshake settings
     */
    public WebSocketClientHandshakeDuplex(WebSocketSettings settings) {
        super(settings.version());
        this.settings = settings;
    }

    /**
     * Initialize the per-channel handshake state container.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        state(context);
    }

    /**
     * Send the opening handshake automatically when auto-handshake is enabled.
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        ClientHandshakeState state = state(context);
        WebSocketAutoHandshakeConfig config = this.settings.autoHandshakeConfig();

        if (config != null && !state.ready && !state.requestPending) {
            context.sendData(WebSocketUtils.createHandshake(this.codecVersion, config.requestPath(), config.headers(), config.cookies()));
        }
    }

    /**
     * Reset every piece of handshake state tracked for the current channel.
     */
    @Override
    protected void resetState(ProtoContext context) {
        resetHandshakeSession(state(context));
    }

    boolean isHandshakeReady(ProtoContext context) {
        return state(context).ready;
    }

    boolean isHandshakePending(ProtoContext context, HttpObject msg) {
        return state(context, msg).requestPending;
    }

    /**
     * Route inbound and outbound HTTP objects through the client handshake flow.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleReceive(context, rcvUp, rcvDown);
        } else {
            return this.handleSend(context, sndUp, sndDown);
        }
    }

    ProtoStatus onReceiveData(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        return this.handleReceive(context, src, dst);
    }

    ProtoStatus onSendData(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        return this.handleSend(context, src, dst);
    }

    ProtoStatus onReceiveMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        return this.handleReceiveMessage(context, msg, dst);
    }

    ProtoStatus onSendMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        return this.handleSendMessage(context, msg, dst);
    }

    /**
     * Consume handshake failures, reset local state, and close the channel when required.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        ClientHandshakeState state = state(context);
        WebSocketHandshakeException handshakeError = this.handshakeError(e);
        if (!state.ready && handshakeError != null) {
            this.resetHandshakeSession(state);
            eh.clear();

            if (handshakeError.closeConnection()) {
                InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                return ProtoStatus.Stop;
            }

            return ProtoStatus.Next;
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus handleSend(ProtoContext context, ProtoRcvData<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            // Do not take from src until dst can accept one message.
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
        ClientHandshakeState state = state(context, msg);

        if (state.ready) {
            dst.offerMessage(msg);
            return ProtoStatus.Next;
        }

        if (state.requestPending) {
            this.warnDropReason(context, "client-snd", "drop outbound data while handshake response is pending.");
            msg.release();
            return ProtoStatus.Next;
        }

        if (!isHttpRequestPart(msg)) {
            this.warnAndDrop(context, "client-snd", msg);
            msg.release();
            this.resetHandshakeSession(state);
            return ProtoStatus.Next;
        }

        if (msg instanceof HttpRequest) {
            if (state.requestParts.isActive()) {
                this.releaseBuffers(state);
                state.requestParts.reset();
            }
            if (state.ready || state.requestPending) {
                this.resetHandshakeSession(state);
            }

            HttpRequest request = (HttpRequest) msg;
            if (request.streamId() > 0 && request.protocolVersion().majorVersion() == 2) {
                state = this.bindStateToStream(context, state, request.streamId());
            }
        }

        ProtoSndQueueView<HttpObject> bufferedQueue = this.ensureBufferedRequestQueue(context, state, msg, dst);
        if (!bufferedQueue.offerMessage(msg)) {
            this.warnDropReason(context, "client-snd", "drop outbound handshake request because the pending subqueue has no remaining slot.");
            msg.release();
            this.resetHandshakeSession(state);
            return ProtoStatus.Stop;
        }

        state.requestParts.appendRequest(msg);
        boolean complete = state.requestParts.isComplete();
        if (!complete && this.isHttp2HeaderOnlyRequestBoundary(msg, state.requestParts)) {
            complete = true;
        }
        if (!complete) {
            return ProtoStatus.Next;
        }

        if (!rememberHandshakeData(state, state.requestParts) || !isCompatible(state.version)) {
            this.warnDropReason(context, "client-snd", "drop non-websocket or incompatible handshake request.");
            this.releaseBuffers(state);
            state.requestParts.reset();
            this.discardHandshakeRequestSnapshot(state);
            return ProtoStatus.Next;
        }

        state = this.bindPendingStateToStream(context, state);

        state.ready = false;
        this.flushBufferedRequest(state);
        state.requestParts.reset();
        return ProtoStatus.Next;
    }

    private boolean rememberHandshakeData(ClientHandshakeState state, HttpMessageParts request) {
        WebSocketVersion version = detectVersion(request);
        if (version == null) {
            return false;
        }

        boolean standardHttp2 = InternalUtils.isStandardHttp2WebSocketRequest(request);

        String requestKey = null;
        String requestKey1 = null;
        String requestKey2 = null;
        byte[] requestKey3 = null;
        if (version.isRfc6455Framing()) {
            if (!standardHttp2) {
                requestKey = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY);
                if (requestKey == null || requestKey.trim().isEmpty()) {
                    return false;
                }
                requestKey = requestKey.trim();
            }
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

        state.requestPending = true;
        state.requestStreamId = request.streamId();
        state.standardHttp2 = standardHttp2;
        state.version = version;
        state.path = request.uri();
        state.host = request.header(HttpHeaderNames.HOST);
        state.origin = request.header(HttpHeaderNames.ORIGIN);
        state.protocols = request.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        state.extensions = request.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        state.key = requestKey;
        state.key1 = requestKey1;
        state.key2 = requestKey2;
        state.key3 = requestKey3;
        return true;
    }

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
        ClientHandshakeState state = state(context, msg);
        if (state.ready) {
            dst.offerMessage(msg);
            return ProtoStatus.Next;
        }

        if (!isHttpResponsePart(msg)) {
            this.warnAndDrop(context, "client-rcv", msg);
            msg.release();
            this.resetHandshakeSession(state);
            return ProtoStatus.Next;
        }

        boolean complete = false;
        try {
            if (msg instanceof HttpResponse) {
                if (state.responseParts.isActive()) {
                    state.responseParts.reset();
                }
                if (state.ready) {
                    state.ready = false;
                }
            }

            state.responseParts.appendOwnedResponse(msg);
            complete = state.responseParts.isComplete();
            if (!complete && this.isHttp2HeaderOnlyHandshakeBoundary(msg, state.responseParts)) {
                complete = true;
            }
            if (!complete) {
                return ProtoStatus.Next;
            }

            if (!state.requestPending) {
                return ProtoStatus.Next;
            }

            String subProtocol;
            try {
                this.verifyUpgrade(state.standardHttp2, state.version, state.protocols, state.extensions, state.responseParts, state.key, state.key1, state.key2, state.key3);
                subProtocol = state.responseParts.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
            } catch (WebSocketHandshakeException e) {
                logger.warn("Websocket client handshake protocol violation: " + e.getMessage());
                this.resetHandshakeSession(state);
                InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                return ProtoStatus.Next;
            }
            String extStr = state.responseParts.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
            WebSocketVersion acceptedVersion = state.version;
            String acceptedPath = state.path;

            try {
                int versionCode = acceptedVersion != null ? acceptedVersion.code() : 13;
                List<WebSocketExtensionResult> extResults = InternalUtils.parseExtensions(extStr);
                List<WebSocketExtensionRuntime> runtimeExt = InternalUtils.resolveRuntimeExtensions(extResults, this.settings);

                WebSocketContext wsContext = new WebSocketContextImpl(false, subProtocol, versionCode, acceptedPath, state.host, state.origin, extResults, runtimeExt);
                this.finishWebSocketUpgrade(context, wsContext, state.requestStreamId);
                state.ready = true;
                discardHandshakeRequestSnapshot(state);
            } catch (Throwable e) {
                logger.error("Error occurred while finalizing websocket client handshake protocol state.", e);
                this.resetHandshakeSession(state);
                InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                return ProtoStatus.Next;
            }
        } finally {
            msg.release();
            if (complete) {
                state.responseParts.reset();
                if (!state.ready) {
                    discardHandshakeRequestSnapshot(state);
                }
            }
        }

        return ProtoStatus.Next;
    }

    private void verifyUpgrade(boolean standardHttp2, WebSocketVersion version, String reqProtocols, String reqExtensions, HttpMessageParts response, String key, String key1, String key2, byte[] key3) {
        if (standardHttp2) {
            if (!InternalUtils.isSuccessfulHttp2WebSocketResponse(response)) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: expected HTTP/2 2xx CONNECT response.");
            }
            validateNegotiatedHeaders(reqProtocols, reqExtensions, response);
            return;
        }

        if (response.status() == null || response.status().code() != HttpStatus.SWITCHING_PROTOCOLS.code()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: expected HTTP 101 Switching Protocols response.");
        }

        String upgrade = response.header(HttpHeaderNames.UPGRADE);
        String connection = response.header(HttpHeaderNames.CONNECTION);
        if (!StringUtils.containsIgnoreCase(connection, HttpHeaderValues.UPGRADE)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: missing Connection: Upgrade response header.");
        }
        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && !StringUtils.equalsIgnoreCase("WebSocket", upgrade)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: missing Upgrade: websocket response header.");
        }

        if (version.isRfc6455Framing()) {
            String acceptKey = response.header(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT);
            String expected = computeAcceptKey(key);
            if (!StringUtils.equals(expected, acceptKey)) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: Sec-WebSocket-Accept does not match the client handshake key.");
            }

            validateNegotiatedHeaders(reqProtocols, reqExtensions, response);
            return;
        }

        byte[] expected = computeHixie76Response(key1, key2, key3);
        ByteBuf content = response.body();
        if (content == null || content.readableBytes() != expected.length) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: invalid Hixie-76 challenge response body.");
        }

        byte[] actual = new byte[expected.length];
        content.getBytes(0, actual, 0, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: invalid Hixie-76 challenge response body.");
            }
        }
    }

    private boolean isHttp2HeaderOnlyHandshakeBoundary(HttpObject msg, HttpMessageParts responseParts) {
        if (!(msg instanceof LastHttpHeaders) || responseParts == null || responseParts.protocolVersion() == null) {
            return false;
        }
        if (responseParts.protocolVersion().majorVersion() != 2) {
            return false;
        }
        if (!InternalUtils.isSuccessfulHttp2WebSocketResponse(responseParts) && (responseParts.status() == null || responseParts.status().code() != HttpStatus.SWITCHING_PROTOCOLS.code())) {
            return false;
        }
        ByteBuf body = responseParts.body();
        return body == null || body.readableBytes() == 0;
    }

    private boolean isHttp2HeaderOnlyRequestBoundary(HttpObject msg, HttpMessageParts requestParts) {
        if (!(msg instanceof LastHttpHeaders) || requestParts == null || requestParts.protocolVersion() == null) {
            return false;
        }
        if (requestParts.protocolVersion().majorVersion() != 2) {
            return false;
        }
        ByteBuf body = requestParts.body();
        return body == null || body.readableBytes() == 0;
    }

    private void validateNegotiatedHeaders(String reqProtocols, String reqExtensions, HttpMessageParts response) {
        String selectedProtocol = response.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        validateSelectedSubProtocol(reqProtocols, selectedProtocol);

        String extStr = response.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        this.validateNegotiatedExtensions(reqExtensions, extStr);
    }

    private void validateNegotiatedExtensions(String requestedExtensions, String negotiatedExtensions) {
        if (StringUtils.isBlank(negotiatedExtensions)) {
            return;
        }

        List<WebSocketExtension> supports = this.settings.extensionSupports();
        if (supports.isEmpty()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: negotiated extensions are disabled by current settings.");
        }

        List<WebSocketExtensionResult> requested = InternalUtils.parseExtensions(requestedExtensions);
        List<WebSocketExtensionResult> negotiated = InternalUtils.parseExtensions(negotiatedExtensions);
        this.ensureNoDuplicateExtensions(negotiated, "websocket upgrade failed: duplicated negotiated websocket extension: ");

        for (WebSocketExtensionResult negotiatedItem : negotiated) {
            WebSocketExtension support = this.findExtensionSupport(supports, negotiatedItem.name());
            if (support == null) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: unsupported negotiated websocket extension: " + negotiatedItem.name());
            }

            String requestedHeader = this.findSingleExtensionHeaderValue(requested, negotiatedItem.name(), "websocket upgrade failed: duplicated requested websocket extension: " + negotiatedItem.name());
            support.validateClientExtensions(this.codecVersion, requestedHeader, negotiatedItem.asHeaderValue());
        }
    }

    private void validateSelectedSubProtocol(String reqProtocols, String selected) {
        List<String> selectedValues = parseHeaderValues(selected);
        if (selectedValues.isEmpty()) {
            return;
        }
        if (selectedValues.size() != 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected more than one websocket sub-protocol.");
        }

        List<String> requestedValues = parseHeaderValues(reqProtocols);
        if (requestedValues.isEmpty()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket sub-protocol.");
        }
        if (!requestedValues.contains(selectedValues.get(0))) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsupported websocket sub-protocol.");
        }
    }

    private ClientHandshakeState state(ProtoContext context) {
        ClientHandshakeState state = context.context(ClientHandshakeState.class);
        return state != null ? state : state(context, null);
    }

    private ClientHandshakeState state(ProtoContext context, HttpObject msg) {
        if (msg == null || msg.streamId() <= 0) {
            ClientHandshakeState localState = context.context(ClientHandshakeState.class);
            if (localState != null) {
                return localState;
            }
        }

        ClientHandshakeStateStore store = stateStore(context);

        PartitionKey key = partitionKey(context, msg);
        ClientHandshakeState state = store.states.get(key);
        if (state == null) {
            ClientHandshakeState newState = new ClientHandshakeState();
            ClientHandshakeState oldState = store.states.putIfAbsent(key, newState);
            state = oldState != null ? oldState : newState;
        }

        if (msg != null && msg.streamId() > 0 && !state.requestPending) {
            ClientHandshakeState pendingState = findPendingState(store, msg.streamId());
            if (pendingState != null) {
                store.states.put(key, pendingState);
                state = pendingState;
            }
        }

        context.context(ClientHandshakeState.class, state);
        return state;
    }

    private ClientHandshakeState state(ProtoContext context, long streamId) {
        ClientHandshakeStateStore store = stateStore(context);
        PartitionKey key = PartitionKey.newKey(streamId);
        ClientHandshakeState state = store.states.get(key);
        if (state == null) {
            ClientHandshakeState newState = new ClientHandshakeState();
            ClientHandshakeState oldState = store.states.putIfAbsent(key, newState);
            state = oldState != null ? oldState : newState;
        }
        context.context(ClientHandshakeState.class, state);
        return state;
    }

    private ClientHandshakeStateStore stateStore(ProtoContext context) {
        ClientHandshakeStateStore shared = context.rootContext(ClientHandshakeStateStore.class);
        if (shared != null) {
            return shared;
        }

        ClientHandshakeStateStore store = new ClientHandshakeStateStore();
        ClientHandshakeStateStore rootStore = context.rootContext(ClientHandshakeStateStore.class, store);
        if (rootStore != null) {
            return rootStore;
        }

        SoChannel<?> channel = context.getChannel();
        Object attr = channel != null ? channel.getAttribute(STATE_STORE_KEY) : null;
        if (attr instanceof ClientHandshakeStateStore) {
            return (ClientHandshakeStateStore) attr;
        }

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

    private ClientHandshakeState bindPendingStateToStream(ProtoContext context, ClientHandshakeState state) {
        long streamId = state.requestStreamId;
        if (streamId <= 0) {
            return state;
        }

        ClientHandshakeState streamState = state(context, streamId);
        if (streamState == state) {
            return state;
        }

        streamState.requestPending = state.requestPending;
        streamState.ready = state.ready;
        streamState.requestStreamId = state.requestStreamId;
        streamState.standardHttp2 = state.standardHttp2;
        streamState.version = state.version;
        streamState.path = state.path;
        streamState.host = state.host;
        streamState.origin = state.origin;
        streamState.protocols = state.protocols;
        streamState.extensions = state.extensions;
        streamState.key = state.key;
        streamState.key1 = state.key1;
        streamState.key2 = state.key2;
        streamState.key3 = state.key3;
        streamState.bufferedRequestViewRef = state.bufferedRequestViewRef;
        state.bufferedRequestViewRef = null;
        this.discardHandshakeRequestSnapshot(state);
        return streamState;
    }

    private ClientHandshakeState bindStateToStream(ProtoContext context, ClientHandshakeState state, long streamId) {
        if (streamId <= 0) {
            return state;
        }

        ClientHandshakeState streamState = state(context, streamId);
        if (streamState == state) {
            state.requestStreamId = streamId;
            return state;
        }

        streamState.requestPending = state.requestPending;
        streamState.ready = state.ready;
        streamState.requestStreamId = streamId;
        streamState.standardHttp2 = state.standardHttp2;
        streamState.version = state.version;
        streamState.path = state.path;
        streamState.host = state.host;
        streamState.origin = state.origin;
        streamState.protocols = state.protocols;
        streamState.extensions = state.extensions;
        streamState.key = state.key;
        streamState.key1 = state.key1;
        streamState.key2 = state.key2;
        streamState.key3 = state.key3;
        streamState.bufferedRequestViewRef = state.bufferedRequestViewRef;
        state.bufferedRequestViewRef = null;
        return streamState;
    }

    private static ClientHandshakeState findPendingState(ClientHandshakeStateStore store, long streamId) {
        if (store == null || streamId <= 0) {
            return null;
        }
        for (ClientHandshakeState item : store.states.values()) {
            if (item != null && item.requestPending && item.requestStreamId == streamId) {
                return item;
            }
        }
        return null;
    }

    private void discardHandshakeRequestSnapshot(ClientHandshakeState state) {
        state.requestPending = false;
        state.requestStreamId = 0;
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
    }

    private void resetHandshakeSession(ClientHandshakeState state) {
        releaseBuffers(state);
        state.requestParts.reset();
        state.responseParts.reset();
        discardHandshakeRequestSnapshot(state);
        state.ready = false;
    }

    private void releaseBuffers(ClientHandshakeState state) {
        if (state.bufferedRequestViewRef != null) {
            state.bufferedRequestViewRef.discard();
            state.bufferedRequestViewRef = null;
        }
    }

    private ProtoSndQueueView<HttpObject> ensureBufferedRequestQueue(ProtoContext context, ClientHandshakeState state, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        if (state.bufferedRequestViewRef != null) {
            return state.bufferedRequestViewRef;
        }

        String queueKey = BUFFER_QUEUE_PREFIX + partitionKey(context, msg).getKey();
        ProtoSndQueueView<HttpObject> subQueue = dst.subQueue(queueKey);
        state.bufferedRequestViewRef = subQueue;
        return subQueue;
    }

    private void flushBufferedRequest(ClientHandshakeState state) {
        if (state.bufferedRequestViewRef != null) {
            state.bufferedRequestViewRef.push();
            state.bufferedRequestViewRef = null;
        }
    }
}