/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

/**
 * Manages HTTP sessions with creation, retrieval, and expiration.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface SessionManager {

    /** Returns the session for the given ID, or null if expired/not found */
    HttpSession getSession(String sessionId);

    /** Creates a new session with a generated ID */
    HttpSession createSession();

    /** Invalidates the given session */
    void invalidateSession(String sessionId);

    /** Returns the default max inactive interval */
    int getDefaultMaxInactiveInterval();

    /** Sets the default max inactive interval for new sessions (in seconds) */
    void setDefaultMaxInactiveInterval(int seconds);

    /** Cleans up expired sessions */
    void cleanExpiredSessions();
}
