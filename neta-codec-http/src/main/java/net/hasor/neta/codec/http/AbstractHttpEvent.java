/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Common base class for HTTP-related event objects.
 * <p>
 * This base class provides protocol-independent stream identification and a one-shot release lifecycle,
 * so protocol families such as HTTP/2, HTTP/3, and WebSocket do not need to reimplement the same event
 * state management.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public abstract class AbstractHttpEvent implements HttpEvent {
    private long    streamId;
    private boolean released;

    /**
     * Return the stream identifier associated with this event.
     * @return the stream identifier
     */
    @Override
    public final long streamId() {
        return this.streamId;
    }

    /**
     * Set the stream identifier associated with this event.
     * @param streamId stream identifier
     * @return the current event object
     */
    @Override
    public AbstractHttpEvent streamId(long streamId) {
        this.streamId = streamId;
        this.released = false;
        return this;
    }

    /**
     * Release the event state and trigger subclass-specific cleanup.
     */
    @Override
    public final void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.streamId = 0L;
        this.doRelease();
    }

    /**
     * Provide an extension point for subclass-specific cleanup.
     */
    protected void doRelease() {
    }
}
