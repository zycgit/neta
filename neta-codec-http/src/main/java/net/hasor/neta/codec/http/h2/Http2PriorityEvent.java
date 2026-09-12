/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
/**
 * Event corresponding to an HTTP/2 PRIORITY frame.
 * <p>
 * This event carries the dependency-tree information defined by RFC 9113 Section 6.3.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Local side proactively adjusts priority
 *   Application Handler   ProtoContext         Http2ObjectEncoder       Remote peer
 *          |                  |                      |                      |
 *          | fireEvent(...)   |                      |                      |
 *          |----------------->|                      |                      |
 *          |                  | onEvent(PRIORITY)    |                      |
 *          |                  |--------------------->|                      |
 *          |                  |                      | sendPriority()       |
 *          |                  |                      |--------------------->|
 *          |                  |                      |   PRIORITY frame     |
 * </pre><pre>
 * Remote endpoint sends a PRIORITY frame
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | PRIORITY frame         |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | fireEvent(remote)    |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2PriorityEvent   |
 *      |                        |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2PriorityEvent extends AbstractHttp2Event {
    private final long    streamDependency;
    private final int     weight;
    private final boolean exclusive;

    /**
     * Creates a PRIORITY event.
     * @param streamId the current stream ID
     * @param streamDependency the dependent stream ID
     * @param weight the weight value
     * @param exclusive whether the dependency is exclusive
     */
    public Http2PriorityEvent(long streamId, long streamDependency, int weight, boolean exclusive) {
        this.streamId(streamId);
        this.streamDependency = streamDependency;
        this.weight = weight;
        this.exclusive = exclusive;
    }

    /**
     * Returns the dependent stream ID.
     */
    public long streamDependency() {
        return this.streamDependency;
    }

    /**
     * Returns the weight value.
     */
    public int weight() {
        return this.weight;
    }

    /**
     * Returns whether the dependency is exclusive.
     */
    public boolean exclusive() {
        return this.exclusive;
    }

    @Override
    public String toString() {
        return "Http2PriorityEvent{streamId=" + this.streamId() + ", streamDependency=" + this.streamDependency + ", weight=" + this.weight + ", exclusive=" + this.exclusive + '}';
    }
}
