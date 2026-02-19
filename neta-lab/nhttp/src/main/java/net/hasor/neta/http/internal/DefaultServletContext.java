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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.http.ServletContext;
import net.hasor.neta.http.SessionManager;

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
