/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.codec.http.AbstractHttpEvent;
/**
 * Base type for HTTP/2 network events.
 * <p>
 * This type is never instantiated directly. Concrete event objects are created by specific
 * subclasses inside the HTTP/2 message-layer codecs and then published through
 * {@code context.fireEvent(...)} or {@code context.fireEventRcv(...)}.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Event produced locally by the protocol layer or application
 *   Application Handler     Http2ObjectEncoder        ProtoContext
 *          |                      |                      |
 *          | fireEvent(...)       |                      |
 *          |--------------------->|                      |
 *          |                      | new ConcreteEvent    |
 *          |                      | remote(false)        |
 *          |                      | fireEvent(...) /     |
 *          |                      | fireEventRcv(...)    |
 *          |                      |--------------------->|
 *          |                      |                      |
 * </pre><pre>
 * Event triggered by a remote frame
 *   Remote frame           Http2ObjectDecoder        ProtoContext
 *      |                        |                      |
 *      | inbound frame          |                      |
 *      |----------------------->|                      |
 *      |                        | new ConcreteEvent    |
 *      |                        | remote(true)         |
 *      |                        | fireEvent(...)       |
 *      |                        |--------------------->|
 *      |                        |                      |
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public abstract class AbstractHttp2Event extends AbstractHttpEvent {
    private boolean remote;

    /**
     * Returns whether this event was triggered by the remote endpoint.
     */
    public final boolean isRemote() {
        return this.remote;
    }

    /**
     * Marks whether this event originates from the remote endpoint.
     * @param remote whether the event originates remotely
     * @return the current event instance
     */
    public final <T extends AbstractHttp2Event> T remote(boolean remote) {
        this.remote = remote;
        return (T) this;
    }

    @Override
    protected void doRelease() {
        this.remote = false;
    }
}
