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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.hasor.neta.codec.http.websocket.extension.PerMessageDeflateSupport;
import net.hasor.neta.codec.http.websocket.extension.WebSocketClientExtensionValidator;
import net.hasor.neta.codec.http.websocket.extension.WebSocketExtensionSupport;
import net.hasor.neta.codec.http.websocket.extension.WebSocketServerExtensionSelector;

/**
 * Central WebSocket settings entry.
 * <p>
 * This type is the home for WebSocket handshake, extension, and later frame/runtime options.
 */
public class WebSocketSettings {
    private final WebSocketVersion                  version;
    private final WebSocketAutoHandshakeConfig      autoHandshakeConfig;
    private final WebSocketServerExtensionSelector  serverExtensionSelector;
    private final WebSocketClientExtensionValidator clientExtensionValidator;
    private final WebSocketHandshakeAuthorizer      handshakeAuthorizer;
    private final List<WebSocketExtensionSupport>   extensionSupports;

    public static Builder builder(WebSocketVersion version) {
        return new Builder(version);
    }

    public static WebSocketSettings of(WebSocketVersion version) {
        return builder(version).build();
    }

    private WebSocketSettings(Builder builder) {
        this.version = builder.version;
        this.autoHandshakeConfig = builder.autoHandshakeConfig;
        this.serverExtensionSelector = builder.serverExtensionSelector;
        this.clientExtensionValidator = builder.clientExtensionValidator;
        this.handshakeAuthorizer = builder.handshakeAuthorizer;
        this.extensionSupports = Collections.unmodifiableList(new ArrayList<>(builder.extensionSupports));
    }

    public WebSocketVersion version() {
        return this.version;
    }

    public WebSocketAutoHandshakeConfig autoHandshakeConfig() {
        return this.autoHandshakeConfig;
    }

    public WebSocketServerExtensionSelector serverExtensionSelector() {
        return this.serverExtensionSelector;
    }

    public WebSocketClientExtensionValidator clientExtensionValidator() {
        return this.clientExtensionValidator;
    }

    public WebSocketHandshakeAuthorizer handshakeAuthorizer() {
        return this.handshakeAuthorizer;
    }

    public List<WebSocketExtensionSupport> extensionSupports() {
        return this.extensionSupports;
    }

    public static class Builder {
        private final WebSocketVersion                  version;
        private       WebSocketAutoHandshakeConfig      autoHandshakeConfig;
        private       WebSocketServerExtensionSelector  serverExtensionSelector;
        private       WebSocketClientExtensionValidator clientExtensionValidator;
        private       WebSocketHandshakeAuthorizer      handshakeAuthorizer;
        private final List<WebSocketExtensionSupport>   extensionSupports = new ArrayList<>(1);

        private Builder(WebSocketVersion version) {
            this.version = Objects.requireNonNull(version, "version is null");
            this.handshakeAuthorizer = (event, c) -> c.accept();
        }

        public Builder autoHandshakeConfig(WebSocketAutoHandshakeConfig autoHandshakeConfig) {
            this.autoHandshakeConfig = autoHandshakeConfig;
            return this;
        }

        public Builder handshakeAuthorizer(WebSocketHandshakeAuthorizer handshakeAuthorizer) {
            this.handshakeAuthorizer = handshakeAuthorizer != null ? handshakeAuthorizer : (event, c) -> c.accept();
            return this;
        }

        public Builder serverExtensionSelector(WebSocketServerExtensionSelector serverExtensionSelector) {
            this.serverExtensionSelector = serverExtensionSelector;

            if (serverExtensionSelector instanceof WebSocketExtensionSupport) {
                this.registerExtensionSupport((WebSocketExtensionSupport) serverExtensionSelector);
            }

            return this;
        }

        public Builder clientExtensionValidator(WebSocketClientExtensionValidator clientExtensionValidator) {
            this.clientExtensionValidator = clientExtensionValidator;

            if (clientExtensionValidator instanceof WebSocketExtensionSupport) {
                this.registerExtensionSupport((WebSocketExtensionSupport) clientExtensionValidator);
            }

            return this;
        }

        public Builder extensionSupport(WebSocketExtensionSupport extensionSupport) {
            this.registerExtensionSupport(extensionSupport);
            return this;
        }

        public Builder usePerMessageDeflateDefaults() {
            PerMessageDeflateSupport support = PerMessageDeflateSupport.instance();
            this.serverExtensionSelector = support;
            this.clientExtensionValidator = support;

            this.registerExtensionSupport(support);
            return this;
        }

        private void registerExtensionSupport(WebSocketExtensionSupport extensionSupport) {
            if (extensionSupport != null && !this.extensionSupports.contains(extensionSupport)) {
                this.extensionSupports.add(extensionSupport);
            }
        }

        public WebSocketSettings build() {
            return new WebSocketSettings(this);
        }
    }
}