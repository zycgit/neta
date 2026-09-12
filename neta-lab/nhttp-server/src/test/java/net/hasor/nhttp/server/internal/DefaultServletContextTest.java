/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;

import static org.junit.Assert.*;

import org.junit.Test;

import net.hasor.neta.codec.http.cors.CorsConfig;

/**
 * Tests for {@link DefaultServletContext}.
 */
public class DefaultServletContextTest {

    @Test
    public void testServerName() {
        DefaultServletContext ctx = new DefaultServletContext("MyServer", "", null, new DefaultSessionManager());
        assertEquals("MyServer", ctx.getServerName());
    }

    @Test
    public void testDefaultServerName() {
        DefaultServletContext ctx = new DefaultServletContext(null, null, null, new DefaultSessionManager());
        assertEquals("Neta-HTTP", ctx.getServerName());
    }

    @Test
    public void testContextPath() {
        DefaultServletContext ctx = new DefaultServletContext("S", "/app", null, new DefaultSessionManager());
        assertEquals("/app", ctx.getContextPath());
    }

    @Test
    public void testDefaultContextPath() {
        DefaultServletContext ctx = new DefaultServletContext("S", null, null, new DefaultSessionManager());
        assertEquals("", ctx.getContextPath());
    }

    @Test
    public void testAttributes() {
        DefaultServletContext ctx = new DefaultServletContext("S", "", null, new DefaultSessionManager());

        assertNull(ctx.getAttribute("key"));
        ctx.setAttribute("key", "value");
        assertEquals("value", ctx.getAttribute("key"));

        ctx.setAttribute("key", null); // should remove
        assertNull(ctx.getAttribute("key"));

        ctx.setAttribute("key2", 42);
        ctx.removeAttribute("key2");
        assertNull(ctx.getAttribute("key2"));
    }

    @Test
    public void testCorsConfig() {
        CorsConfig cors = CorsConfig.builder().allowAnyOrigin().build();
        DefaultServletContext ctx = new DefaultServletContext("S", "", cors, new DefaultSessionManager());
        assertSame(cors, ctx.getCorsConfig());
    }

    @Test
    public void testNullCorsConfig() {
        DefaultServletContext ctx = new DefaultServletContext("S", "", null, new DefaultSessionManager());
        assertNull(ctx.getCorsConfig());
    }

    @Test
    public void testSessionManager() {
        DefaultSessionManager manager = new DefaultSessionManager();
        DefaultServletContext ctx = new DefaultServletContext("S", "", null, manager);
        assertSame(manager, ctx.getSessionManager());
    }
}
