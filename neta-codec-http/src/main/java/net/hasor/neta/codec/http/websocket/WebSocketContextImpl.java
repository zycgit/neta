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
import net.hasor.neta.codec.http.websocket.extension.WebSocketExtensionResult;
import net.hasor.neta.codec.http.websocket.extension.WebSocketRuntimeExtension;

/**
 * Default immutable {@link WebSocketContext} implementation.
 * <p>
 * Created by handshake duplexers after upgrade succeeds and then attached to the pipeline context.
 */
class WebSocketContextImpl implements WebSocketContext {
    private final boolean                         server;
    private final String                          subProtocol;
    private final int                             version;
    private final String                          requestPath;
    private final List<WebSocketExtensionResult>  extResults;
    private final List<WebSocketRuntimeExtension> runExtensions;

    /**
     * Creates a new WebSocketContext.
     * @param server true for server-side, false for client-side
     * @param subProtocol the negotiated sub-protocol (or null)
     * @param version the WebSocket version (typically 13)
     * @param requestPath the request URI path of the upgrade request
     * @param extensions negotiated extensions (or empty list)
     */
    WebSocketContextImpl(boolean server, String subProtocol, int version, String requestPath, List<String> extensions) {
        this(server, subProtocol, version, requestPath, WebSocketUtils.parseExtensions(extensions), Collections.emptyList());
    }

    WebSocketContextImpl(boolean server, String subProtocol, int version, String requestPath,//
            List<WebSocketExtensionResult> extResults, List<WebSocketRuntimeExtension> runExtensions) {
        this.server = server;
        this.subProtocol = subProtocol;
        this.version = version;
        this.requestPath = requestPath;
        this.extResults = extResults != null ? Collections.unmodifiableList(extResults) : Collections.emptyList();
        this.runExtensions = runExtensions != null ? Collections.unmodifiableList(runExtensions) : Collections.emptyList();
    }

    @Override
    public boolean isReady() {
        return true; // Instance is only created after handshake completes
    }

    @Override
    public boolean isServer() {
        return this.server;
    }

    @Override
    public boolean isClient() {
        return !this.server;
    }

    @Override
    public String subProtocol() {
        return this.subProtocol;
    }

    @Override
    public int version() {
        return this.version;
    }

    @Override
    public String requestPath() {
        return this.requestPath;
    }

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

    @Override
    public List<WebSocketExtensionResult> extensionList() {
        return this.extResults;
    }

    public List<WebSocketRuntimeExtension> runtimeList() {
        return this.runExtensions;
    }

    /**
     * Returns {@code true} if the named extension has been negotiated.
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
