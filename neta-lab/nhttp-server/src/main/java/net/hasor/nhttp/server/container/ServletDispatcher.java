/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.container;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.hasor.nhttp.server.*;

/**
 * Routes incoming requests to the matching servlet or WebSocket handler based on
 * URL-pattern matching, and constructs the filter chain for each request.
 *
 * <h3>URL-pattern matching priority (highest → lowest)</h3>
 * <ol>
 *   <li><b>Exact match</b> — e.g. {@code /foo/bar}</li>
 *   <li><b>Longest path-prefix match</b> — e.g. {@code /foo/*}</li>
 *   <li><b>Extension match</b> — e.g. {@code *.html}</li>
 *   <li><b>Default servlet</b> — catches everything else</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>Registration methods ({@link #addServlet}, {@link #addFilter},
 * {@link #addWebSocket}, {@link #setDefaultServlet}) use a write lock so that
 * they are safe to call concurrently with ongoing request dispatches (which hold
 * a read lock during the lookup phase).</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServletDispatcher {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Guarded by 'lock'
    private final List<ServletMapping>          servletMappings   = new ArrayList<>();
    private final List<FilterMapping>           filterMappings    = new ArrayList<>();
    private final Map<String, WebSocketMapping> webSocketMappings = new LinkedHashMap<>();
    private HttpServlet                         defaultServlet;

    // -------------------------------------------------------------------------
    // Registration (write-locked)
    // -------------------------------------------------------------------------

    /** Registers a servlet for a URL pattern. May be called after the server has started. */
    public void addServlet(String urlPattern, HttpServlet servlet) {
        this.lock.writeLock().lock();
        try {
            this.servletMappings.add(new ServletMapping(urlPattern, servlet));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /** Registers a filter for a URL pattern. May be called after the server has started. */
    public void addFilter(String urlPattern, Filter filter) {
        this.lock.writeLock().lock();
        try {
            this.filterMappings.add(new FilterMapping(urlPattern, filter));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /** Registers a WebSocket handler for an exact path. May be called after the server has started. */
    public void addWebSocket(String path, WebSocketHandler handler) {
        this.lock.writeLock().lock();
        try {
            this.webSocketMappings.put(normalizePath(path), new WebSocketMapping(path, handler));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /** Sets the default servlet for unmatched paths. May be called after the server has started. */
    public void setDefaultServlet(HttpServlet defaultServlet) {
        this.lock.writeLock().lock();
        try {
            this.defaultServlet = defaultServlet;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Lookup (read-locked)
    // -------------------------------------------------------------------------

    /**
     * Finds the WebSocket handler registered for {@code path}, or {@code null} if none.
     * Uses exact matching only (WebSocket paths are not pattern-matched).
     */
    public WebSocketHandler findWebSocketHandler(String path) {
        this.lock.readLock().lock();
        try {
            WebSocketMapping mapping = this.webSocketMappings.get(normalizePath(path));
            return mapping != null ? mapping.handler : null;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatches the request to the matching servlet with all applicable filters applied.
     * Sends a 404 error if no servlet is found and no default servlet is registered.
     */
    public void dispatch(ServletRequest request, ServletResponse response) throws IOException {
        // Snapshot the routing tables under read lock so that concurrent registrations
        // do not interfere with this dispatch.
        HttpServlet servlet;
        List<Filter> matchingFilters;
        this.lock.readLock().lock();
        try {
            String path = request.getRequestPath();
            servlet = findServlet(path);
            if (servlet == null) {
                servlet = this.defaultServlet;
            }
            matchingFilters = findFilters(path);
        } finally {
            this.lock.readLock().unlock();
        }

        if (servlet == null) {
            response.sendError(404, "Not Found: " + request.getRequestPath());
            return;
        }

        // Execute the filter chain (no lock held — filters/servlets do user work)
        FilterChain chain = buildFilterChain(matchingFilters, servlet);
        chain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialises all registered filters and servlets.
     * Should be called once before the server starts accepting requests.
     */
    public void init(ServletContext context) throws Exception {
        this.lock.readLock().lock();
        List<FilterMapping> filters;
        List<ServletMapping> servlets;
        HttpServlet def;
        try {
            filters = new ArrayList<>(this.filterMappings);
            servlets = new ArrayList<>(this.servletMappings);
            def = this.defaultServlet;
        } finally {
            this.lock.readLock().unlock();
        }

        for (FilterMapping fm : filters) {
            fm.filter.init(context);
        }
        for (ServletMapping sm : servlets) {
            sm.servlet.init(context);
        }
        if (def != null) {
            def.init(context);
        }
    }

    /**
     * Destroys all registered filters and servlets.
     * Should be called once during server shutdown.
     */
    public void destroy() {
        this.lock.readLock().lock();
        List<FilterMapping> filters;
        List<ServletMapping> servlets;
        HttpServlet def;
        try {
            filters = new ArrayList<>(this.filterMappings);
            servlets = new ArrayList<>(this.servletMappings);
            def = this.defaultServlet;
        } finally {
            this.lock.readLock().unlock();
        }

        for (FilterMapping fm : filters) {
            fm.filter.destroy();
        }
        for (ServletMapping sm : servlets) {
            sm.servlet.destroy();
        }
        if (def != null) {
            def.destroy();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers (caller must hold at least read lock)
    // -------------------------------------------------------------------------

    private HttpServlet findServlet(String path) {
        // 1. Exact match
        for (ServletMapping mapping : this.servletMappings) {
            if (matchExact(mapping.pattern, path)) {
                return mapping.servlet;
            }
        }
        // 2. Longest path-prefix match
        HttpServlet bestMatch = null;
        int bestLength = -1;
        for (ServletMapping mapping : this.servletMappings) {
            int len = matchPathPrefixLength(mapping.pattern, path);
            if (len > bestLength) {
                bestLength = len;
                bestMatch = mapping.servlet;
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }
        // 3. Extension match
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

    private static FilterChain buildFilterChain(List<Filter> filters, HttpServlet servlet) {
        // Build the chain tail: call the servlet
        FilterChain tail = (req, resp) -> servlet.service(req, resp);
        // Wrap filters around the tail in reverse order so the first filter runs first
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter filter = filters.get(i);
            FilterChain next = tail;
            tail = (req, resp) -> filter.doFilter(req, resp, next);
        }
        return tail;
    }

    // -------------------------------------------------------------------------
    // Pattern matching utilities
    // -------------------------------------------------------------------------

    private static boolean matchPattern(String pattern, String path) {
        return matchExact(pattern, path) || matchPathPrefixLength(pattern, path) >= 0 || matchExtension(pattern, path);
    }

    private static boolean matchExact(String pattern, String path) {
        return pattern.equals(path);
    }

    /**
     * Returns the prefix length if {@code pattern} is a path-prefix pattern (e.g. {@code /foo/*})
     * and it matches {@code path}; otherwise returns {@code -1}.
     */
    private static int matchPathPrefixLength(String pattern, String path) {
        if (!pattern.endsWith("/*")) {
            return -1;
        }
        String prefix = pattern.substring(0, pattern.length() - 2);
        if (path.equals(prefix) || path.startsWith(prefix + "/")) {
            return prefix.length();
        }
        return -1;
    }

    /** Returns {@code true} if {@code pattern} is an extension pattern (e.g. {@code *.html}) and matches {@code path}. */
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
        return path.startsWith("/") ? path : "/" + path;
    }

    // -------------------------------------------------------------------------
    // Inner mapping records
    // -------------------------------------------------------------------------

    private static final class ServletMapping {
        final String      pattern;
        final HttpServlet servlet;

        ServletMapping(String pattern, HttpServlet servlet) {
            this.pattern = pattern;
            this.servlet = servlet;
        }
    }

    private static final class FilterMapping {
        final String pattern;
        final Filter filter;

        FilterMapping(String pattern, Filter filter) {
            this.pattern = pattern;
            this.filter = filter;
        }
    }

    private static final class WebSocketMapping {
        final String           path;
        final WebSocketHandler handler;

        WebSocketMapping(String path, WebSocketHandler handler) {
            this.path = path;
            this.handler = handler;
        }
    }
}
