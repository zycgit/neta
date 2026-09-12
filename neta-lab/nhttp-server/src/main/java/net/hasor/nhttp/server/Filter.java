/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;
import java.io.IOException;

/**
 * Filter interface for pre/post processing of HTTP requests,
 * similar to javax.servlet.Filter.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Filter {

    /** Called when the filter is initialized */
    default void init(ServletContext context) throws Exception {
    }

    /**
     * Performs filtering. Implementations should call {@code chain.doFilter(request, response)}
     * to continue the chain, or handle the request themselves.
     */
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException;

    /** Called when the filter is being removed */
    default void destroy() {
    }
}
