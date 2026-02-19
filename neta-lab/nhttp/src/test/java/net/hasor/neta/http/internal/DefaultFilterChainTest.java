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
package net.hasor.neta.http.internal;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.http.Filter;
import net.hasor.neta.http.HttpServlet;
import net.hasor.neta.http.ServletRequest;
import net.hasor.neta.http.ServletResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link DefaultFilterChain}.
 */
public class DefaultFilterChainTest {

    @Test
    public void testEmptyFilterChainCallsServlet() throws IOException {
        AtomicInteger servletCalled = new AtomicInteger(0);
        HttpServlet servlet = new HttpServlet() {
            @Override
            public void service(ServletRequest request, ServletResponse response) {
                servletCalled.incrementAndGet();
            }
        };

        DefaultFilterChain chain = new DefaultFilterChain(Collections.emptyList(), servlet);
        chain.doFilter(null, null);
        assertEquals(1, servletCalled.get());
    }

    @Test
    public void testFilterChainOrder() throws IOException {
        StringBuilder order = new StringBuilder();

        Filter filter1 = (req, resp, chain) -> {
            order.append("F1-before ");
            chain.doFilter(req, resp);
            order.append("F1-after ");
        };

        Filter filter2 = (req, resp, chain) -> {
            order.append("F2-before ");
            chain.doFilter(req, resp);
            order.append("F2-after ");
        };

        HttpServlet servlet = new HttpServlet() {
            @Override
            public void service(ServletRequest request, ServletResponse response) {
                order.append("SERVLET ");
            }
        };

        DefaultFilterChain chain = new DefaultFilterChain(Arrays.asList(filter1, filter2), servlet);
        chain.doFilter(null, null);
        assertEquals("F1-before F2-before SERVLET F2-after F1-after ", order.toString());
    }

    @Test
    public void testFilterCanShortCircuit() throws IOException {
        AtomicInteger servletCalled = new AtomicInteger(0);

        Filter blockingFilter = (req, resp, chain) -> {
            // intentionally NOT calling chain.doFilter
        };

        HttpServlet servlet = new HttpServlet() {
            @Override
            public void service(ServletRequest request, ServletResponse response) {
                servletCalled.incrementAndGet();
            }
        };

        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(blockingFilter), servlet);
        chain.doFilter(null, null);
        assertEquals(0, servletCalled.get()); // servlet should NOT be called
    }

    @Test
    public void testNullServlet() throws IOException {
        AtomicInteger filterCalled = new AtomicInteger(0);

        Filter filter = (req, resp, chain) -> {
            filterCalled.incrementAndGet();
            chain.doFilter(req, resp); // should not throw even with null servlet
        };

        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter), null);
        chain.doFilter(null, null);
        assertEquals(1, filterCalled.get());
    }

    @Test
    public void testSingleFilter() throws IOException {
        AtomicReference<String> value = new AtomicReference<>();

        Filter filter = (req, resp, chain) -> {
            value.set("filtered");
            chain.doFilter(req, resp);
        };

        HttpServlet servlet = new HttpServlet() {
            @Override
            public void service(ServletRequest request, ServletResponse response) {
                // do nothing
            }
        };

        DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter), servlet);
        chain.doFilter(null, null);
        assertEquals("filtered", value.get());
    }
}
