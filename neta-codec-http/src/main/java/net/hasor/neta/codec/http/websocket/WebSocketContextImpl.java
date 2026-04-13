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
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;

/**
 * Default implementation of {@link WebSocketContext}.
 * <p>
 * Created after the handshake duplexer completes the upgrade successfully and
 * then bound into the pipeline context.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class WebSocketContextImpl implements WebSocketContext {
    private final boolean                         server;
    private final String                          subProtocol;
    private final int                             version;
    private final String                          requestPath;
    private final String                          requestHost;
    private final String                          requestOrigin;
    private final List<WebSocketExtensionResult>  extResults;
    private final List<WebSocketExtensionRuntime> runExtensions;

    /**
     * Create a new websocket context.
     * @param server whether the current side is the server; {@code true} means server side
     * @param subProtocol negotiated sub-protocol, may be {@code null}
     * @param version negotiated websocket version number
     * @param requestPath URI path from the handshake request
     * @param extensions negotiated extensions, may be an empty list
     */
    WebSocketContextImpl(boolean server, String subProtocol, int version, String requestPath, String requestHost, String requestOrigin, List<String> extensions) {
        this(server, subProtocol, version, requestPath, requestHost, requestOrigin, InternalUtils.parseExtensions(extensions), Collections.emptyList());
    }

    /**
     * Create a new websocket context from pre-parsed extension results and runtime extensions.
     * @param server whether the current side is the server
     * @param subProtocol negotiated sub-protocol
     * @param version negotiated websocket version number
     * @param requestPath handshake request path
     * @param extResults structured extension results
     * @param runExtensions runtime extension list
     */
    WebSocketContextImpl(boolean server, String subProtocol, int version, String requestPath, String requestHost, String requestOrigin,//
            List<WebSocketExtensionResult> extResults, List<WebSocketExtensionRuntime> runExtensions) {
        this.server = server;
        this.subProtocol = subProtocol;
        this.version = version;
        this.requestPath = requestPath;
        this.requestHost = requestHost;
        this.requestOrigin = requestOrigin;
        this.extResults = extResults != null ? Collections.unmodifiableList(extResults) : Collections.emptyList();
        this.runExtensions = runExtensions != null ? Collections.unmodifiableList(runExtensions) : Collections.emptyList();
    }

    /**
     * Return whether the context is ready.
     */
    @Override
    public boolean isReady() {
        return true; // Instances are created only after handshake completion.
    }

    /**
     * Return whether the current endpoint is the server side.
     */
    @Override
    public boolean isServer() {
        return this.server;
    }

    /**
     * Return whether the current endpoint is the client side.
     */
    @Override
    public boolean isClient() {
        return !this.server;
    }

    /**
     * Return the negotiated sub-protocol.
     */
    @Override
    public String subProtocol() {
        return this.subProtocol;
    }

    /**
     * Return the negotiated websocket version number.
     */
    @Override
    public int version() {
        return this.version;
    }

    /**
     * Return the handshake request path.
     */
    @Override
    public String requestPath() {
        return this.requestPath;
    }

    /**
     * Return the request Host header captured during the handshake.
     */
    @Override
    public String requestHost() {
        return this.requestHost;
    }

    /**
     * Return the request Origin header captured during the handshake.
     */
    @Override
    public String requestOrigin() {
        return this.requestOrigin;
    }

    /**
     * Return the negotiated extension string in comma-separated form.
     */
    @Override
    public String extensions() {
        if (this.extResults.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.extResults.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(this.extResults.get(i).asHeaderValue());
        }

        return sb.toString();
    }

    /**
     * Return the structured extension negotiation results.
     */
    @Override
    public List<WebSocketExtensionResult> extensionList() {
        return this.extResults;
    }

    /**
     * Return the runtime extensions installed on the current connection.
     */
    public List<WebSocketExtensionRuntime> runtimeList() {
        return this.runExtensions;
    }

    /**
     * Return {@code true} when an extension with the specified name was negotiated successfully.
     */
    @Override
    public boolean hasExtension(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }

        for (WebSocketExtensionResult result : this.extensionList()) {
            if (result != null && StringUtils.equalsIgnoreCase(result.name(), name)) {
                return true;
            }
        }
        return false;
    }
}
