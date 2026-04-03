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
package net.hasor.neta.codec.http.websocket.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeException;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeRequest;
import net.hasor.neta.codec.http.websocket.WebSocketVersion;

/**
 * First-batch built-in extension support limited to RFC 6455 single-extension
 * {@code permessage-deflate} negotiation.
 */
public class PerMessageDeflateSupport implements WebSocketServerExtensionSelector, WebSocketClientExtensionValidator {
    public static final String                    EXTENSION_NAME = "permessage-deflate";
    private static final PerMessageDeflateSupport INSTANCE       = new PerMessageDeflateSupport();

    public static PerMessageDeflateSupport instance() {
        return INSTANCE;
    }

    @Override
    public String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        verifyRfc6455(request.version(), "websocket handshake failed: built-in extension support is currently limited to RFC6455.");

        String requested = normalizeSinglePerMessageDeflate(request.requestedExtensions(),
                "websocket handshake failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        String proposed = normalizeSinglePerMessageDeflate(proposedExtensions,
                "websocket handshake failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (proposed == null) {
            return null;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsolicited websocket extension.");
        }
        return proposed;
    }

    @Override
    public void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions) {
        verifyRfc6455(version, "websocket upgrade failed: built-in extension support is currently limited to RFC6455.");

        String requested = normalizeSinglePerMessageDeflate(requestedExtensions,
                "websocket upgrade failed: built-in extension support currently accepts only one requested extension and it must be permessage-deflate.");
        String negotiated = normalizeSinglePerMessageDeflate(negotiatedExtensions,
                "websocket upgrade failed: built-in extension support currently accepts only one negotiated extension and it must be permessage-deflate.");
        if (negotiated == null) {
            return;
        }
        if (requested == null) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket extension.");
        }
    }

    private static void verifyRfc6455(WebSocketVersion version, String message) {
        if (version == null || !version.isRfc6455Framing()) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static String normalizeSinglePerMessageDeflate(String headerValue, String invalidMessage) {
        List<String> values = parseHeaderValues(headerValue);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        String value = values.get(0);
        if (!StringUtils.equals(EXTENSION_NAME, value)) {
            throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
        }
        return EXTENSION_NAME;
    }

    private static List<String> parseHeaderValues(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return Collections.emptyList();
        }

        String[] parts = headerValue.split(",");
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part != null ? part.trim() : null;
            if (StringUtils.isNotBlank(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        return values;
    }
}