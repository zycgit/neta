/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.nhttp.server.ServletContext;
import net.hasor.nhttp.server.SessionManager;

/**
 * Default implementation of {@link ServletContext}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultServletContext implements ServletContext {
    private final String              serverName;
    private final String              contextPath;
    private final CorsConfig          corsConfig;
    private final SessionManager      sessionManager;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public DefaultServletContext(String serverName, String contextPath, CorsConfig corsConfig, SessionManager sessionManager) {
        this.serverName = serverName != null ? serverName : "Neta-HTTP";
        this.contextPath = contextPath != null ? contextPath : "";
        this.corsConfig = corsConfig;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getServerName() {
        return this.serverName;
    }

    @Override
    public String getContextPath() {
        return this.contextPath;
    }

    @Override
    public Object getAttribute(String name) {
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            this.attributes.remove(name);
        } else {
            this.attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        this.attributes.remove(name);
    }

    @Override
    public CorsConfig getCorsConfig() {
        return this.corsConfig;
    }

    @Override
    public SessionManager getSessionManager() {
        return this.sessionManager;
    }
}
