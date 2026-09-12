/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.nhttp.server.HttpSession;

/**
 * Default in-memory implementation of {@link HttpSession}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class DefaultHttpSession implements HttpSession {
    private final String              id;
    private final long                creationTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile long             lastAccessedTime;
    private volatile int              maxInactiveInterval;
    private volatile boolean          valid      = true;
    private volatile boolean          isNew      = true;

    public DefaultHttpSession(String id, int maxInactiveInterval) {
        this.id = id;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = this.creationTime;
        this.maxInactiveInterval = maxInactiveInterval;
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public long getCreationTime() {
        return this.creationTime;
    }

    @Override
    public long getLastAccessedTime() {
        return this.lastAccessedTime;
    }

    /** Called to update the last accessed time */
    public void touch() {
        this.lastAccessedTime = System.currentTimeMillis();
        this.isNew = false;
    }

    @Override
    public int getMaxInactiveInterval() {
        return this.maxInactiveInterval;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return this.attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) {
            this.attributes.remove(name);
        } else {
            this.attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        this.attributes.remove(name);
    }

    @Override
    public Map<String, Object> getAttributes() {
        checkValid();
        return Collections.unmodifiableMap(this.attributes);
    }

    @Override
    public void invalidate() {
        this.valid = false;
        this.attributes.clear();
    }

    @Override
    public boolean isValid() {
        if (!this.valid) {
            return false;
        }
        // check timeout
        if (this.maxInactiveInterval > 0) {
            long elapsed = System.currentTimeMillis() - this.lastAccessedTime;
            if (elapsed > (long) this.maxInactiveInterval * 1000) {
                invalidate();
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isNew() {
        return this.isNew;
    }

    private void checkValid() {
        if (!isValid()) {
            throw new IllegalStateException("Session has been invalidated: " + this.id);
        }
    }
}
