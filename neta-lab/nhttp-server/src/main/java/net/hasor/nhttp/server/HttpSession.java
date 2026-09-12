/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;
import java.util.Map;

/**
 * HTTP Session interface for storing user-specific data across requests.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface HttpSession {

    /** Returns the unique session ID */
    String getId();

    /** Returns the creation time (millis since epoch) */
    long getCreationTime();

    /** Returns the last access time (millis since epoch) */
    long getLastAccessedTime();

    /** Returns the max inactive interval in seconds */
    int getMaxInactiveInterval();

    /** Sets the maximum inactive interval in seconds. Negative means no timeout. */
    void setMaxInactiveInterval(int interval);

    /** Returns a session attribute, or null */
    Object getAttribute(String name);

    /** Sets a session attribute */
    void setAttribute(String name, Object value);

    /** Removes a session attribute */
    void removeAttribute(String name);

    /** Returns all attribute names and values */
    Map<String, Object> getAttributes();

    /** Invalidates this session, removing all attributes */
    void invalidate();

    /** Returns true if this session is still valid */
    boolean isValid();

    /** Returns true if this is a new session (not yet sent to client) */
    boolean isNew();
}
