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
    private final int     streamDependency;
    private final int     weight;
    private final boolean exclusive;

    /**
     * Creates a PRIORITY event.
     * @param streamId the current stream ID
     * @param streamDependency the dependent stream ID
     * @param weight the weight value
     * @param exclusive whether the dependency is exclusive
     */
    public Http2PriorityEvent(int streamId, int streamDependency, int weight, boolean exclusive) {
        this.streamId(streamId);
        this.streamDependency = streamDependency;
        this.weight = weight;
        this.exclusive = exclusive;
    }

    /**
     * Returns the dependent stream ID.
     */
    public int streamDependency() {
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
