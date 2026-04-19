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
