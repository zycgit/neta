/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Extension SPI that covers negotiation, validation, and runtime creation.
 * <p>
 * One implementation can select server-side extension results, validate
 * client-side negotiated headers, parse the agreed header value, and create a
 * runtime extension for a single websocket connection.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public interface WebSocketExtension extends WebSocketExtensionSelector, WebSocketExtensionValidator {
    /**
     * Return the websocket extension name handled by this extension.
     * @return extension name
     */
    String extensionName();

    /**
     * Parse one negotiated extension header value.
     * @param headerValue negotiated extension header value
     * @return parsed extension result, or {@code null} when this support does not accept it
     */
    WebSocketExtensionResult parseNegotiatedExtension(String headerValue);

    /**
     * Create the runtime instance for one negotiated extension result.
     * @param negotiatedExtension negotiated extension result
     * @return runtime instance
     */
    WebSocketExtensionRuntime createRuntimeExtension(WebSocketExtensionResult negotiatedExtension);
}
