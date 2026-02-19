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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.neta.http.*;

/**
 * Routes incoming requests to the appropriate servlet or WebSocket handler
 * based on URL pattern matching. Also manages the filter chain.
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServletDispatcher {
    private final List<ServletMapping>          servletMappings   = new ArrayList<>();
    private final List<FilterMapping>           filterMappings    = new ArrayList<>();
    private final Map<String, WebSocketMapping> webSocketMappings = new LinkedHashMap<>();
    private       HttpServlet                   defaultServlet;

    private static boolean matchPattern(String pattern, String path) {
        return matchExact(pattern, path) || matchPathPrefix(pattern, path) >= 0 || matchExtension(pattern, path);
    }

    private static boolean matchExact(String pattern, String path) {
        return pattern.equals(path);
    }

    /**
     * Path prefix matching: "/foo/*" matches "/foo/bar", "/foo/baz".
     * Returns the prefix length if matched, -1 otherwise.
     */
    private static int matchPathPrefix(String pattern, String path) {
        if (!pattern.endsWith("/*")) {
            return -1;
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        if (path.equals(prefix) || path.startsWith(prefix + "/")) {
            return prefix.length();
        }
        return -1;
    }

    /**
     * Extension matching: "*.html" matches "/foo/bar.html"
     */
    private static boolean matchExtension(String pattern, String path) {
        if (!pattern.startsWith("*.")) {
            return false;
        }
        String extension = pattern.substring(1); // ".html"
        return path.endsWith(extension);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /** Registers a servlet for a URL pattern */
    public void addServlet(String urlPattern, HttpServlet servlet) {
        this.servletMappings.add(new ServletMapping(urlPattern, servlet));
    }

    /** Registers a filter for a URL pattern */
    public void addFilter(String urlPattern, Filter filter) {
        this.filterMappings.add(new FilterMapping(urlPattern, filter));
    }

    /** Registers a WebSocket handler for a path */
    public void addWebSocket(String path, WebSocketHandler handler) {
        this.webSocketMappings.put(normalizePath(path), new WebSocketMapping(path, handler));
    }

    /** Sets the default servlet for unmatched paths */
    public void setDefaultServlet(HttpServlet defaultServlet) {
        this.defaultServlet = defaultServlet;
    }

    /** Finds the matching WebSocket handler for a path */
    public WebSocketHandler findWebSocketHandler(String path) {
        WebSocketMapping mapping = this.webSocketMappings.get(normalizePath(path));
        return mapping != null ? mapping.handler : null;
    }

    // --- URL Pattern Matching ---

    /**
     * Dispatches a request to the matching servlet with applicable filters.
     */
    public void dispatch(ServletRequest request, ServletResponse response) throws IOException {
        String path = request.getRequestPath();

        // find matching servlet
        HttpServlet servlet = findServlet(path);
        if (servlet == null) {
            servlet = this.defaultServlet;
        }

        if (servlet == null) {
            response.sendError(404, "Not Found: " + path);
            return;
        }

        // find matching filters
        List<Filter> matchingFilters = findFilters(path);

        // build and execute filter chain
        DefaultFilterChain chain = new DefaultFilterChain(matchingFilters, servlet);
        chain.doFilter(request, response);
    }

    private HttpServlet findServlet(String path) {
        // exact match first
        for (ServletMapping mapping : this.servletMappings) {
            if (matchExact(mapping.pattern, path)) {
                return mapping.servlet;
            }
        }
        // longest path prefix match
        HttpServlet bestMatch = null;
        int bestLength = -1;
        for (ServletMapping mapping : this.servletMappings) {
            int len = matchPathPrefix(mapping.pattern, path);
            if (len > bestLength) {
                bestLength = len;
                bestMatch = mapping.servlet;
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }
        // extension match
        for (ServletMapping mapping : this.servletMappings) {
            if (matchExtension(mapping.pattern, path)) {
                return mapping.servlet;
            }
        }
        return null;
    }

    private List<Filter> findFilters(String path) {
        List<Filter> result = new ArrayList<>();
        for (FilterMapping mapping : this.filterMappings) {
            if (matchPattern(mapping.pattern, path)) {
                result.add(mapping.filter);
            }
        }
        return result;
    }

    /** Initialize all servlets and filters */
    public void init(ServletContext context) throws Exception {
        for (FilterMapping fm : this.filterMappings) {
            fm.filter.init(context);
        }
        for (ServletMapping sm : this.servletMappings) {
            sm.servlet.init(context);
        }
        if (this.defaultServlet != null) {
            this.defaultServlet.init(context);
        }
    }

    /** Destroy all servlets and filters */
    public void destroy() {
        for (FilterMapping fm : this.filterMappings) {
            fm.filter.destroy();
        }
        for (ServletMapping sm : this.servletMappings) {
            sm.servlet.destroy();
        }
        if (this.defaultServlet != null) {
            this.defaultServlet.destroy();
        }
    }

    // --- Inner classes ---

    private static class ServletMapping {
        final String      pattern;
        final HttpServlet servlet;

        ServletMapping(String pattern, HttpServlet servlet) {
            this.pattern = pattern;
            this.servlet = servlet;
        }
    }

    private static class FilterMapping {
        final String pattern;
        final Filter filter;

        FilterMapping(String pattern, Filter filter) {
            this.pattern = pattern;
            this.filter = filter;
        }
    }

    private static class WebSocketMapping {
        final String           path;
        final WebSocketHandler handler;

        WebSocketMapping(String path, WebSocketHandler handler) {
            this.path = path;
            this.handler = handler;
        }
    }
}
