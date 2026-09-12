/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.nhttp.server.HttpSession;
import net.hasor.nhttp.server.SessionManager;

/**
 * Default in-memory session manager. Sessions are stored in a ConcurrentHashMap.
 * Expired sessions are cleaned up on access or by calling {@link #cleanExpiredSessions()}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultSessionManager implements SessionManager {
    private final Map<String, DefaultHttpSession> sessions                   = new ConcurrentHashMap<>();
    private volatile int                          defaultMaxInactiveInterval = 1800; // 30 minutes

    @Override
    public HttpSession getSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        DefaultHttpSession session = this.sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.isValid()) {
            this.sessions.remove(sessionId);
            return null;
        }
        session.touch();
        return session;
    }

    @Override
    public HttpSession createSession() {
        String id = generateSessionId();
        DefaultHttpSession session = new DefaultHttpSession(id, this.defaultMaxInactiveInterval);
        this.sessions.put(id, session);
        return session;
    }

    @Override
    public void invalidateSession(String sessionId) {
        DefaultHttpSession session = this.sessions.remove(sessionId);
        if (session != null) {
            session.invalidate();
        }
    }

    @Override
    public int getDefaultMaxInactiveInterval() {
        return this.defaultMaxInactiveInterval;
    }

    @Override
    public void setDefaultMaxInactiveInterval(int seconds) {
        this.defaultMaxInactiveInterval = seconds;
    }

    @Override
    public void cleanExpiredSessions() {
        Iterator<Map.Entry<String, DefaultHttpSession>> it = this.sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DefaultHttpSession> entry = it.next();
            if (!entry.getValue().isValid()) {
                it.remove();
            }
        }
    }

    protected String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
