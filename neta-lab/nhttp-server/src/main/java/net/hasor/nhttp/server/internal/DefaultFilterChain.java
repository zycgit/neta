/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
