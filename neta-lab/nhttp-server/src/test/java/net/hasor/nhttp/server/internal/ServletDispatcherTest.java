/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.hasor.nhttp.server.container.ServletDispatcher;
import net.hasor.nhttp.server.*;

/**
 * Tests for {@link ServletDispatcher}.
 */
public class ServletDispatcherTest {

    private static MockServletRequest mockRequest(String path) {
        return new MockServletRequest(path, "GET");
    }

    private static MockServletRequest mockRequest(String path, String method) {
        return new MockServletRequest(path, method);
    }

    @Test
    public void testExactMatch() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean called = new AtomicBoolean(false);

        dispatcher.addServlet("/hello", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/hello"), response);
        assertTrue(called.get());
    }

    @Test
    public void testPathPrefixMatch() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean called = new AtomicBoolean(false);

        dispatcher.addServlet("/api/*", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/api/users"), response);
        assertTrue(called.get());
    }

    @Test
    public void testExtensionMatch() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean called = new AtomicBoolean(false);

        dispatcher.addServlet("*.html", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/page.html"), response);
        assertTrue(called.get());
    }

    @Test
    public void testDefaultServlet() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean called = new AtomicBoolean(false);

        dispatcher.setDefaultServlet(new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/unknown-path"), response);
        assertTrue(called.get());
    }

    @Test
    public void testNoMatch404() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/missing"), response);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void testFilterApplied() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicInteger filterCalled = new AtomicInteger(0);
        AtomicInteger servletCalled = new AtomicInteger(0);

        dispatcher.addFilter("/*", (req, resp, chain) -> {
            filterCalled.incrementAndGet();
            chain.doFilter(req, resp);
        });

        dispatcher.addServlet("/test", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                servletCalled.incrementAndGet();
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/test"), response);
        assertEquals(1, filterCalled.get());
        assertEquals(1, servletCalled.get());
    }

    @Test
    public void testFilterOnlyMatchingPath() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicInteger filterCalled = new AtomicInteger(0);

        dispatcher.addFilter("/admin/*", (req, resp, chain) -> {
            filterCalled.incrementAndGet();
            chain.doFilter(req, resp);
        });

        dispatcher.setDefaultServlet(new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
            }
        });

        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/public/page"), response);
        assertEquals(0, filterCalled.get()); // filter should not match
    }

    @Test
    public void testWebSocketHandlerRegistration() {
        ServletDispatcher dispatcher = new ServletDispatcher();
        WebSocketHandler handler = new WebSocketHandler() {
        };
        dispatcher.addWebSocket("/ws", handler);

        assertSame(handler, dispatcher.findWebSocketHandler("/ws"));
        assertNull(dispatcher.findWebSocketHandler("/other"));
    }

    @Test
    public void testWebSocketHandlerPathNormalization() {
        ServletDispatcher dispatcher = new ServletDispatcher();
        WebSocketHandler handler = new WebSocketHandler() {
        };
        dispatcher.addWebSocket("ws", handler);

        assertSame(handler, dispatcher.findWebSocketHandler("/ws"));
    }

    @Test
    public void testMultipleServlets() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicInteger s1 = new AtomicInteger(0);
        AtomicInteger s2 = new AtomicInteger(0);

        dispatcher.addServlet("/one", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                s1.incrementAndGet();
            }
        });
        dispatcher.addServlet("/two", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                s2.incrementAndGet();
            }
        });

        MockServletResponse r1 = new MockServletResponse();
        MockServletResponse r2 = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/one"), r1);
        dispatcher.dispatch(mockRequest("/two"), r2);

        assertEquals(1, s1.get());
        assertEquals(1, s2.get());
    }

    @Test
    public void testInitAndDestroy() throws Exception {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean initCalled = new AtomicBoolean(false);
        AtomicBoolean destroyCalled = new AtomicBoolean(false);

        dispatcher.addServlet("/test", new HttpServlet() {
            @Override
            public void init(ServletContext context) {
                initCalled.set(true);
            }

            @Override
            public void destroy() {
                destroyCalled.set(true);
            }
        });

        DefaultServletContext ctx = new DefaultServletContext("test", "", null, new DefaultSessionManager());
        dispatcher.init(ctx);
        assertTrue(initCalled.get());

        dispatcher.destroy();
        assertTrue(destroyCalled.get());
    }

    @Test
    public void testPathPrefixMatchExactPath() throws IOException {
        ServletDispatcher dispatcher = new ServletDispatcher();
        AtomicBoolean called = new AtomicBoolean(false);

        dispatcher.addServlet("/api/*", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        });

        // "/api" should also match "/api/*"
        MockServletResponse response = new MockServletResponse();
        dispatcher.dispatch(mockRequest("/api"), response);
        assertTrue(called.get());
    }
}
