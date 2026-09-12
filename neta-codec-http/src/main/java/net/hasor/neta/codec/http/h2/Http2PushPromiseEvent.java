/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.codec.http.HttpHeaders;
/**
 * Event corresponding to an HTTP/2 PUSH_PROMISE frame.
 * <p>
 * This event is published after the message layer fully reassembles the promised request header
 * block and completes HPACK decoding.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Server side proactively initiates server push
 *   Application Handler   ProtoContext         Http2ObjectEncoder       Remote peer
 *          |                  |                      |                      |
 *          | fireEvent(...)   |                      |                      |
 *          |----------------->|                      |                      |
 *          |                  | onEvent(PUSH_PROMISE)|                      |
 *          |                  |--------------------->|                      |
 *          |                  |                      | sendPushPromise()    |
 *          |                  |                      |--------------------->|
 *          |                  |                      | PUSH_PROMISE frames  |
 * </pre><pre>
 * Remote endpoint sends PUSH_PROMISE
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | PUSH_PROMISE /         |                      |                      |
 *      | CONTINUATION           |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | decodeHeaders()      |                      |
 *      |                        | fireEvent(remote)    |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2PushPromiseEvent|
 *      |                        |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2PushPromiseEvent extends AbstractHttp2Event {
    private final long        promisedStreamId;
    private final HttpHeaders headers;

    /**
     * Creates a PUSH_PROMISE event.
     * @param streamId the current stream ID
     * @param promisedStreamId the promised stream ID
     * @param headers the decoded request headers
     */
    public Http2PushPromiseEvent(long streamId, long promisedStreamId, HttpHeaders headers) {
        this.streamId(streamId);
        this.promisedStreamId = promisedStreamId;
        this.headers = headers;
    }

    /**
     * Returns the promised stream ID.
     */
    public long promisedStreamId() {
        return this.promisedStreamId;
    }

    /**
     * Returns the decoded request headers.
     */
    public HttpHeaders headers() {
        return this.headers;
    }

    @Override
    public String toString() {
        return "Http2PushPromiseEvent{streamId=" + this.streamId() + ", promisedStreamId=" + this.promisedStreamId + ", headerCount=" + this.headers.headerSize() + '}';
    }
}
