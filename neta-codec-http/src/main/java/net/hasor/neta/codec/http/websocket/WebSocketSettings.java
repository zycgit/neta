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
import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.websocket.extensions.DeflateFrameSupport;
import net.hasor.neta.codec.http.websocket.extensions.PerMessageDeflateSupport;
import net.hasor.neta.codec.http.websocket.extensions.XWebkitDeflateFrameSupport;
/**
 * Unified entry point for WebSocket settings.
 * <p>
 * This type centralizes configuration related to the WebSocket handshake,
 * extensions, and subsequent frame-layer and runtime behavior.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-07
 */
public class WebSocketSettings {
    static final WebSocketHandshakeAuthorizer DEFAULT_HANDSHAKE_AUTHORIZER = (event, c) -> c.accept();
    private static final WebSocketSettings     DEFAULT_V0                  = new WebSocketSettings(WebSocketVersion.V0);
    private static final WebSocketSettings     DEFAULT_V7                  = new WebSocketSettings(WebSocketVersion.V7);
    private static final WebSocketSettings     DEFAULT_V8                  = new WebSocketSettings(WebSocketVersion.V8);
    private static final WebSocketSettings     DEFAULT_V13                 = new WebSocketSettings(WebSocketVersion.V13);

    private final WebSocketVersion         version;
    private WebSocketAutoHandshakeConfig   handshakeAutoConfig;
    private WebSocketHandshakeAuthorizer   handshakeAuthorizer;
    private final List<WebSocketExtension> extensionSupports;

    /**
     * Create a settings object with default values.
     * @param version WebSocket version
     * @return settings object
     */
    public static WebSocketSettings of(WebSocketVersion version) {
        return new WebSocketSettings(version);
    }

    /**
     * Create a settings object.
     * @param version WebSocket version
     */
    public WebSocketSettings(WebSocketVersion version) {
        this.version = Objects.requireNonNull(version, "version is null");
        this.handshakeAuthorizer = DEFAULT_HANDSHAKE_AUTHORIZER;
        this.extensionSupports = new ArrayList<>(1);
    }

    /**
     * Return the WebSocket version.
     */
    public WebSocketVersion version() {
        return this.version;
    }

    /**
     * Return the automatic handshake configuration.
     */
    public WebSocketAutoHandshakeConfig autoHandshakeConfig() {
        return this.handshakeAutoConfig;
    }

    /**
     * Return the handshake authorizer.
     */
    public WebSocketHandshakeAuthorizer handshakeAuthorizer() {
        return this.handshakeAuthorizer;
    }

    /**
     * Return the list of registered extension support implementations.
     */
    public List<WebSocketExtension> extensionSupports() {
        return Collections.unmodifiableList(this.extensionSupports);
    }

    /**
     * Set the automatic handshake configuration.
     * @param autoHandshakeConfig automatic handshake configuration
     * @return current settings object
     */
    public WebSocketSettings autoHandshakeConfig(WebSocketAutoHandshakeConfig autoHandshakeConfig) {
        this.handshakeAutoConfig = autoHandshakeConfig;
        return this;
    }

    /**
     * Set the handshake authorizer.
     * @param handshakeAuthorizer handshake authorizer
     * @return current settings object
     */
    public WebSocketSettings handshakeAuthorizer(WebSocketHandshakeAuthorizer handshakeAuthorizer) {
        this.handshakeAuthorizer = handshakeAuthorizer != null ? handshakeAuthorizer : DEFAULT_HANDSHAKE_AUTHORIZER;
        return this;
    }

    static boolean isDefaultHandshakeAuthorizer(WebSocketHandshakeAuthorizer handshakeAuthorizer) {
        return handshakeAuthorizer == DEFAULT_HANDSHAKE_AUTHORIZER;
    }

    static WebSocketSettings defaultSettings(WebSocketVersion version) {
        Objects.requireNonNull(version, "version is null");
        switch (version) {
            case V0:
                return DEFAULT_V0;
            case V7:
                return DEFAULT_V7;
            case V8:
                return DEFAULT_V8;
            case V13:
                return DEFAULT_V13;
            default:
                throw new IllegalArgumentException("unsupported websocket version: " + version);
        }
    }

    /**
     * Register an extension support implementation.
     * @param extensionSupport extension support implementation
     * @return current settings object
     */
    public WebSocketSettings extensionSupport(WebSocketExtension extensionSupport) {
        this.registerExtensionSupport(extensionSupport);
        return this;
    }

    /**
     * Register an extension support implementation.
     * @param extensionSupport extension support implementation
     * @return current settings object
     */
    public WebSocketSettings addExtensionSupport(WebSocketExtension extensionSupport) {
        return this.extensionSupport(extensionSupport);
    }

    /**
     * Enable the built-in default permessage-deflate capability.
     * @return current settings object
     */
    public WebSocketSettings usePerMessageDeflateDefaults() {
        return this.addExtensionSupport(PerMessageDeflateSupport.instance());
    }

    /**
     * Enable the built-in default deflate-frame capability.
     * @return current settings object
     */
    public WebSocketSettings useDeflateFrameDefaults() {
        return this.addExtensionSupport(DeflateFrameSupport.instance());
    }

    /**
     * Enable the built-in default x-webkit-deflate-frame capability.
     * @return current settings object
     */
    public WebSocketSettings useXWebkitDeflateFrameDefaults() {
        return this.addExtensionSupport(XWebkitDeflateFrameSupport.instance());
    }

    private void registerExtensionSupport(WebSocketExtension extensionSupport) {
        if (extensionSupport == null) {
            return;
        }

        if (StringUtils.isBlank(extensionSupport.extensionName())) {
            throw new IllegalArgumentException("extensionSupport.extensionName() is blank");
        }

        for (WebSocketExtension registered : this.extensionSupports) {
            if (registered == extensionSupport) {
                return;
            }
            if (registered != null && StringUtils.equalsIgnoreCase(registered.extensionName(), extensionSupport.extensionName())) {
                throw new IllegalArgumentException("duplicated websocket extension support: " + extensionSupport.extensionName());
            }
        }

        this.extensionSupports.add(extensionSupport);
    }
}
