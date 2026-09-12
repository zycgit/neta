/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;
import java.io.IOException;
import java.util.List;
import net.hasor.nhttp.server.*;

/**
 * Default filter chain implementation that processes filters sequentially
 * and ultimately calls the target servlet.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultFilterChain implements FilterChain {
    private final List<Filter> filters;
    private final HttpServlet  servlet;
    private int                currentIndex = 0;

    public DefaultFilterChain(List<Filter> filters, HttpServlet servlet) {
        this.filters = filters;
        this.servlet = servlet;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
        if (this.currentIndex < this.filters.size()) {
            Filter filter = this.filters.get(this.currentIndex++);
            filter.doFilter(request, response, this);
        } else if (this.servlet != null) {
            this.servlet.service(request, response);
        }
    }
}
