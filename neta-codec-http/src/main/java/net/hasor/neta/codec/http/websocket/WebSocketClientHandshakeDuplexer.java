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
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
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
public class WebSocketClientHandshakeDuplexer extends AbstractWebSocketHandshake {
    private static final Logger            logger = LoggerFactory.getLogger(WebSocketClientHandshakeDuplexer.class);
    private final        WebSocketSettings settings;

    private static final class ClientHandshakeState {
        private final HttpMessageParts      requestParts         = new HttpMessageParts();
        private final HttpMessageParts      responseParts        = new HttpMessageParts();
        private final ArrayList<HttpObject> bufferedRequestParts = new ArrayList<>();
        private       boolean               requestPending;
        private       boolean               ready;
        private       WebSocketVersion      version;
        private       String                path;
        private       String                protocols;
        private       String                extensions;
        private       String                key;
        private       String                key1;
        private       String                key2;
        private       byte[]                key3;
    }

    /**
     * Create a client handshake duplexer for the given protocol version.
     * @param codecVersion websocket version to negotiate
     */
    public WebSocketClientHandshakeDuplexer(WebSocketVersion codecVersion) {
        this(WebSocketSettings.of(codecVersion));
    }

    /**
     * Create a client handshake duplexer with automatic handshake settings.
     * @param codecVersion websocket version to negotiate
     * @param autoHandshakeConfig auto-handshake request settings
     */
    public WebSocketClientHandshakeDuplexer(WebSocketVersion codecVersion, WebSocketAutoHandshakeConfig autoHandshakeConfig) {
        this(WebSocketSettings.of(codecVersion).autoHandshakeConfig(autoHandshakeConfig));
    }

    /**
     * Create a client handshake duplexer from the full websocket settings.
     * @param settings websocket handshake settings
     */
    public WebSocketClientHandshakeDuplexer(WebSocketSettings settings) {
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

    /**
     * Route inbound and outbound HTTP objects through the client handshake flow.
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
                InnelUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                return ProtoStatus.Stop;
            }

            return ProtoStatus.Next;
        }

        return ProtoStatus.Next;
    }

    // handleSend
    private ProtoStatus handleSend(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        ClientHandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (state.ready) {
                dst.offerMessage(msg);
                continue;
            }

            if (state.requestPending) {
                this.warnDropReason(context, "client-snd", "drop outbound data while handshake response is pending.");
                msg.release();
                continue;
            }

            if (!isHttpRequestPart(msg)) {
                this.warnAndDrop(context, "client-snd", msg);
                msg.release();
                this.resetHandshakeSession(state);
                continue;
            }

            if (msg instanceof HttpRequest) {
                if (state.requestParts.isActive()) {
                    this.releaseBuffers(state);
                    state.requestParts.reset();
                }
                if (state.ready || state.requestPending) {
                    this.resetHandshakeSession(state);
                }
            }

            state.bufferedRequestParts.add(msg);
            state.requestParts.appendRequest(msg);
            if (!state.requestParts.isComplete()) {
                continue;
            }

            if (!rememberHandshakeData(state, state.requestParts) || !isCompatible(state.version)) {
                this.warnDropReason(context, "client-snd", "drop non-websocket or incompatible handshake request.");
                this.releaseBuffers(state);
                state.requestParts.reset();
                this.discardHandshakeRequestSnapshot(state);
                continue;
            }

            state.ready = false;
            for (HttpObject buffered : state.bufferedRequestParts) {
                dst.offerMessage(buffered);
            }
            state.bufferedRequestParts.clear();
            state.requestParts.reset();
        }
        return ProtoStatus.Next;
    }

    private boolean rememberHandshakeData(ClientHandshakeState state, HttpMessageParts request) {
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

        state.requestPending = true;
        state.version = version;
        state.path = request.uri();
        state.protocols = request.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        state.extensions = request.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        state.key = requestKey;
        state.key1 = requestKey1;
        state.key2 = requestKey2;
        state.key3 = requestKey3;
        return true;
    }

    //
    // handleReceive
    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        ClientHandshakeState state = state(context);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            if (state.ready) {
                dst.offerMessage(msg);
                continue;
            }

            // ERROR1: before handshake finish, only allow HTTP response fragments.
            if (!isHttpResponsePart(msg)) {
                this.warnAndDrop(context, "client-rcv", msg);
                msg.release();
                this.resetHandshakeSession(state);
                continue;
            }

            boolean complete = false;
            try {
                // valid handshake
                if (msg instanceof HttpResponse) {
                    if (state.responseParts.isActive()) {
                        state.responseParts.reset();
                    }
                    if (state.ready) {
                        state.ready = false;
                    }
                }

                // collect data.
                state.responseParts.appendResponse(msg);
                complete = state.responseParts.isComplete();
                if (!complete) {
                    continue;
                }

                // test and waiting for handshake data
                if (!state.requestPending) {
                    continue;
                }

                // verify handshake
                String subProtocol;
                try {
                    this.verifyUpgrade(state.version, state.protocols, state.extensions, state.responseParts, state.key, state.key1, state.key2, state.key3);
                    subProtocol = state.responseParts.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
                } catch (WebSocketHandshakeException e) {
                    logger.warn("Websocket client handshake protocol violation: " + e.getMessage());
                    this.resetHandshakeSession(state);
                    InnelUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
                    return ProtoStatus.Next;
                }
                String extStr = state.responseParts.header(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
                WebSocketVersion acceptedVersion = state.version;
                String acceptedPath = state.path;

                // finsh handshake
                try {
                    int versionCode = acceptedVersion != null ? acceptedVersion.code() : 13;
                    List<WebSocketExtensionResult> extResults = InnelUtils.parseExtensions(extStr);
                    List<WebSocketExtensionRuntime> runtimeExt = InnelUtils.resolveRuntimeExtensions(extResults, this.settings);

                    WebSocketContext wsContext = new WebSocketContextImpl(false, subProtocol, versionCode, acceptedPath, extResults, runtimeExt);
                    this.finishWebSocketUpgrade(context, wsContext, state.requestParts.streamId());
                    state.ready = true;
                    discardHandshakeRequestSnapshot(state);
                } catch (Throwable e) {
                    logger.error("Error occurred while finalizing websocket client handshake protocol state.", e);
                    this.resetHandshakeSession(state);
                    InnelUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
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
        }
        return ProtoStatus.Next;
    }

    private void verifyUpgrade(WebSocketVersion version, String reqProtocols, String reqExtensions, HttpMessageParts response,//
            String key, String key1, String key2, byte[] key3) {
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

        List<WebSocketExtensionResult> requested = InnelUtils.parseExtensions(requestedExtensions);
        List<WebSocketExtensionResult> negotiated = InnelUtils.parseExtensions(negotiatedExtensions);
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

    // tools
    private ClientHandshakeState state(ProtoContext context) {
        ClientHandshakeState state = context.context(ClientHandshakeState.class);
        if (state == null) {
            state = new ClientHandshakeState();
            context.context(ClientHandshakeState.class, state);
        }
        return state;
    }

    // Discard only the remembered request snapshot that is needed to validate the
    // upgrade response. Buffered messages and aggregators belong to the wider
    // handshake session and are reset separately.
    private void discardHandshakeRequestSnapshot(ClientHandshakeState state) {
        state.requestPending = false;
        state.version = null;
        state.path = null;
        state.protocols = null;
        state.extensions = null;
        state.key = null;
        state.key1 = null;
        state.key2 = null;
        state.key3 = null;
    }

    // Reset the whole client opening-handshake session, including buffered request
    // parts and response aggregation state, so a new handshake can start cleanly.
    private void resetHandshakeSession(ClientHandshakeState state) {
        releaseBuffers(state);
        state.requestParts.reset();
        state.responseParts.reset();
        discardHandshakeRequestSnapshot(state);
        state.ready = false;
    }

    private void releaseBuffers(ClientHandshakeState state) {
        for (HttpObject buffered : state.bufferedRequestParts) {
            buffered.release();
        }
        state.bufferedRequestParts.clear();
    }
}