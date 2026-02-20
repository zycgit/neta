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

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link WebSocketContext} implementation ({@link WebSocketContextImpl}).
 */
public class WebSocketContextTest {

    @Test
    public void fromHandshake_basic() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(
            "/chat", "graphql-transport-ws", null);

        assertTrue("Should be ready after handshake", ctx.isReady());
        assertTrue("fromHandshake creates server-side context", ctx.isServer());
        assertFalse("Server should not be client", ctx.isClient());
        assertEquals("Request path should match", "/chat", ctx.requestPath());
        assertEquals("Sub-protocol should match", "graphql-transport-ws", ctx.subProtocol());
        assertEquals("Version should be 13 (RFC 6455)", 13, ctx.version());
        assertNull("No extensions negotiated", ctx.extensions());
    }

    @Test
    public void fromHandshake_withExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(
            "/ws", null, "permessage-deflate, x-webkit-deflate-frame");

        assertNull("No sub-protocol", ctx.subProtocol());
        assertEquals("Extensions should be comma-separated",
            "permessage-deflate, x-webkit-deflate-frame", ctx.extensions());
    }

    @Test
    public void fromHandshake_emptyExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(
            "/ws", null, "");

        assertNull("Empty extensions should return null", ctx.extensions());
    }

    @Test
    public void fromHandshake_nullExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(
            "/ws", null, null);

        assertNull("Null extensions should return null", ctx.extensions());
    }

    @Test
    public void constructor_clientSide() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(
            false, "mqtt", 13, "/mqtt", Collections.emptyList());

        assertFalse("Client-side should report isServer=false", ctx.isServer());
        assertTrue("Client-side should report isClient=true", ctx.isClient());
        assertEquals("mqtt", ctx.subProtocol());
        assertEquals("/mqtt", ctx.requestPath());
    }

    @Test
    public void constructor_withExtensionList() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(
            true, null, 13, "/ws",
            Arrays.asList("permessage-deflate", "x-ext"));

        assertEquals("permessage-deflate, x-ext", ctx.extensions());
    }

    @Test
    public void constructor_nullExtensionList() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(
            true, null, 13, "/ws", null);

        assertNull("Null extension list should return null", ctx.extensions());
    }

    @Test
    public void alwaysReady() {
        // WebSocketContext is only created after handshake, so it's always ready
        WebSocketContextImpl ctx = new WebSocketContextImpl(
            true, null, 13, "/", Collections.emptyList());
        assertTrue("Should always be ready", ctx.isReady());
    }

    @Test
    public void version_rfc6455() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/", null, null);
        assertEquals("RFC 6455 version is 13", 13, ctx.version());
    }
}
