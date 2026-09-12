/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;
import net.hasor.neta.codec.http.cors.CorsConfig;

/**
 * Servlet context interface, providing server-level configuration and registration.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ServletContext {

    // NOTE: NetaHttpServer forward reference — implementations return the live config.

    /** Returns the server name */
    String getServerName();

    /** Returns the context path (root path prefix) */
    String getContextPath();

    /** Returns a context attribute */
    Object getAttribute(String name);

    /** Sets a context attribute */
    void setAttribute(String name, Object value);

    /** Removes a context attribute */
    void removeAttribute(String name);

    /** Returns the CORS configuration, or null if CORS is disabled */
    CorsConfig getCorsConfig();

    /** Returns the session manager */
    SessionManager getSessionManager();

    /**
     * Returns the server configuration.
     * Implementations should return the {@link ServerConfig} that was used to build the server.
     */
    default ServerConfig getServerConfig() {
        throw new UnsupportedOperationException("getServerConfig() is not supported in this context.");
    }
}
