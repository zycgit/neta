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
import net.hasor.neta.codec.http.AbstractHttpEvent;
import net.hasor.neta.codec.http.websocket.extension.WebSocketExtensionResult;

/**
 * Event published when the WebSocket opening handshake succeeds.
 * <p>
 * Wraps the resolved {@link WebSocketContext} and exposes its key handshake results.
 */
public class WebSocketHandshakeEvent extends AbstractHttpEvent {
    private final WebSocketContext context;

    public WebSocketHandshakeEvent(WebSocketContext webSocketContext) {
        if (webSocketContext == null) {
            throw new IllegalArgumentException("webSocketContext must not be null");
        }
        this.context = webSocketContext;
    }

    public boolean isServer() {
        return this.context.isServer();
    }

    public boolean isClient() {
        return this.context.isClient();
    }

    public int version() {
        return this.context.version();
    }

    public String requestPath() {
        return this.context.requestPath();
    }

    public String subProtocol() {
        return this.context.subProtocol();
    }

    public String extensions() {
        return this.context.extensions();
    }

    public List<WebSocketExtensionResult> extensionResults() {
        return this.context.extensionList();
    }

    public boolean hasExtension(String extensionName) {
        return this.context.hasExtension(extensionName);
    }

    @Override
    public String toString() {
        return "WebSocketHandshakeEvent{" + "server=" + this.isServer() + ", version=" + this.version() + ", path='" + this.requestPath() + '\'' + (this.subProtocol() != null ? ", subProtocol='" + this.subProtocol() + '\'' : "") + '}';
    }
}