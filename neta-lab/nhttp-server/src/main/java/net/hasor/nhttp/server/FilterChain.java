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
 * Filter chain interface for invoking the next filter or the target servlet.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface FilterChain {

    /** Invokes the next filter in the chain, or the target servlet if no more filters */
    void doFilter(ServletRequest request, ServletResponse response) throws IOException;
}
