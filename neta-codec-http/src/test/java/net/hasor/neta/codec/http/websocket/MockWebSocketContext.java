/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;

import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;

final class MockWebSocketContext implements WebSocketContext {
    private final boolean                        ready;
    private final boolean                        server;
    private final String                         subProtocol;
    private final int                            version;
    private final String                         requestPath;
    private final String                         extensions;
    private final List<WebSocketExtensionResult> extensionList;

    private MockWebSocketContext(boolean ready, boolean server, int version, String requestPath, String subProtocol, String extensions) {
        this.ready = ready;
        this.server = server;
        this.version = version;
        this.requestPath = requestPath;
        this.subProtocol = subProtocol;
        this.extensions = extensions;
        this.extensionList = StringUtils.isBlank(extensions) ? Collections.emptyList() : WebSocketExtensionResult.parse(extensions);
    }

    static MockWebSocketContext server(WebSocketVersion version, String requestPath) {
        return new MockWebSocketContext(true, true, version.code(), requestPath, null, null);
    }

    static MockWebSocketContext server(WebSocketVersion version, String requestPath, String extensions) {
        return new MockWebSocketContext(true, true, version.code(), requestPath, null, extensions);
    }

    static MockWebSocketContext client(WebSocketVersion version, String requestPath) {
        return new MockWebSocketContext(true, false, version.code(), requestPath, null, null);
    }

    static MockWebSocketContext client(WebSocketVersion version, String requestPath, String extensions) {
        return new MockWebSocketContext(true, false, version.code(), requestPath, null, extensions);
    }

    @Override
    public boolean isReady() {
        return this.ready;
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
    public String requestHost() {
        return null;
    }

    @Override
    public String requestOrigin() {
        return null;
    }

    @Override
    public String extensions() {
        return this.extensions;
    }

    @Override
    public List<WebSocketExtensionResult> extensionList() {
        return this.extensionList;
    }

    @Override
    public boolean hasExtension(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }

        for (WebSocketExtensionResult result : this.extensionList) {
            if (result != null && StringUtils.equalsIgnoreCase(result.name(), name)) {
                return true;
            }
        }
        return false;
    }
}
