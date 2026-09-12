/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import java.util.Arrays;
/**
 * Event describing the closing boundary of an HTTP/2 connection.
 * <p>
 * This event is published by the protocol layer when the remote endpoint sends {@code GOAWAY} or
 * when the local endpoint decides to terminate the connection because of a connection-level protocol
 * error. Applications can observe that boundary and decide whether to retry or release resources
 * without taking over connection management from the protocol layer.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Remote endpoint sends a GOAWAY frame
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | GOAWAY                 |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | fireEvent(remote)    |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2GoawayEvent     |
 *      |                        |                      |--------------------->|
 * </pre><pre>
 * Local side explicitly emits a GOAWAY event
 *   Application Handler   ProtoContext         Http2ObjectEncoder       Remote peer
 *          |                  |                      |                      |
 *          | fireEvent(...)   |                      |                      |
 *          |----------------->|                      |                      |
 *          |                  | onEvent(...)         |                      |
 *          |                  |--------------------->|                      |
 *          |                  |                      | queueControlFrame()  |
 *          |                  |                      |--------------------->|
 *          |                  | Http2GoawayEvent     |                      |
 *          |                  |&lt;---------------------|                      |
 * </pre><pre>
 * Local connection-level protocol error
 *   Http2ObjectDecoder        ProtoContext        Application Handler
 *          |                      |                      |
 *          | onError() /          |                      |
 *          | handleConnectionError|                      |
 *          | fireEvent(local)     |                      |
 *          |--------------------->|                      |
 *          |                      | Http2GoawayEvent     |
 *          |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2GoawayEvent extends AbstractHttp2Event {
    private final long   lastAcceptedId;
    private final long   errorCode;
    private final byte[] debugData;

    /**
     * Creates a GOAWAY event.
     * @param streamId the associated stream ID
     * @param lastAcceptedId the last identifier that the peer still accepts after GOAWAY
     * @param errorCode the protocol error code
     * @param debugData the optional debug payload
     */
    public Http2GoawayEvent(long streamId, long lastAcceptedId, long errorCode, byte[] debugData) {
        this.streamId(streamId);
        this.lastAcceptedId = lastAcceptedId;
        this.errorCode = errorCode;
        this.debugData = debugData == null ? new byte[0] : Arrays.copyOf(debugData, debugData.length);
    }

    /**
     * Returns the last identifier still accepted by the peer after GOAWAY.
     */
    public long lastAcceptedId() {
        return this.lastAcceptedId;
    }

    /**
     * Returns the protocol-related error code, or {@code 0} if none exists.
     */
    public long errorCode() {
        return this.errorCode;
    }

    /**
     * Returns the optional debug payload, or an empty array if none exists.
     */
    public byte[] debugData() {
        return Arrays.copyOf(this.debugData, this.debugData.length);
    }
}
