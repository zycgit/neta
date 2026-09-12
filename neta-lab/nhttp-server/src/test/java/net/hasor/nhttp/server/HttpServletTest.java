/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import net.hasor.nhttp.server.internal.MockServletRequest;
import net.hasor.nhttp.server.internal.MockServletResponse;

/**
 * Tests for {@link HttpServlet}.
 */
public class HttpServletTest {

    @Test
    public void testServiceDispatchesGet() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "GET"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testServiceDispatchesPost() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "POST"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testServiceDispatchesPut() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doPut(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "PUT"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testServiceDispatchesDelete() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doDelete(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "DELETE"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testServiceDispatchesHead() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true); // HEAD defaults to doGet
            }
        };
        servlet.service(new MockServletRequest("/test", "HEAD"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testServiceDispatchesOptions() throws IOException {
        HttpServlet servlet = new HttpServlet() {
        };
        MockServletResponse response = new MockServletResponse();
        servlet.service(new MockServletRequest("/test", "OPTIONS"), response);
        assertEquals(200, response.getStatus());
        assertNotNull(response.getHeader("allow"));
    }

    @Test
    public void testServiceDispatchesPatch() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doPatch(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "PATCH"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testUnsupportedMethodDefault() throws IOException {
        HttpServlet servlet = new HttpServlet() {
        };
        MockServletResponse response = new MockServletResponse();
        servlet.service(new MockServletRequest("/test", "GET"), response);
        assertEquals(405, response.getStatus()); // default doGet sends 405
    }

    @Test
    public void testUnknownMethod() throws IOException {
        HttpServlet servlet = new HttpServlet() {
        };
        MockServletResponse response = new MockServletResponse();
        servlet.service(new MockServletRequest("/test", "UNKNOWN"), response);
        assertEquals(405, response.getStatus());
    }

    @Test
    public void testCaseInsensitiveMethod() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) {
                called.set(true);
            }
        };
        servlet.service(new MockServletRequest("/test", "get"), new MockServletResponse());
        assertTrue(called.get());
    }

    @Test
    public void testInitAndDestroy() throws Exception {
        AtomicBoolean initCalled = new AtomicBoolean(false);
        AtomicBoolean destroyCalled = new AtomicBoolean(false);

        HttpServlet servlet = new HttpServlet() {
            @Override
            public void init(ServletContext context) {
                initCalled.set(true);
            }

            @Override
            public void destroy() {
                destroyCalled.set(true);
            }
        };

        servlet.init(null);
        assertTrue(initCalled.get());

        servlet.destroy();
        assertTrue(destroyCalled.get());
    }
}
