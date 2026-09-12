/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
/**
 * Event describing an HTTP/2 stream reset.
 * <p>
 * The protocol layer publishes this event when the remote endpoint sends an inbound
 * {@code RST_STREAM} frame or when the local endpoint detects a stream-level protocol error and
 * actively resets the stream. This keeps stream lifecycle management inside the protocol layer while
 * still exposing the result to application handlers.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Local side proactively resets a stream
 *   Application Handler   ProtoContext         Http2ObjectEncoder       Remote peer
 *          |                  |                      |                      |
 *          | fireEvent(...)   |                      |                      |
 *          |----------------->|                      |                      |
 *          |                  | onEvent(RESET)       |                      |
 *          |                  |--------------------->|                      |
 *          |                  |                      | sendResetStream()    |
 *          |                  |                      |--------------------->|
 *          |                  | Http2ResetEvent      |                      |
 *          |                  |&lt;---------------------|                      |
 * </pre><pre>
 * Remote endpoint sends RST_STREAM
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | RST_STREAM frame       |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | fireEvent(remote)    |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2ResetEvent      |
 *      |                        |                      |--------------------->|
 * </pre><pre>
 * Local stream-level protocol error
 *   Http2ObjectDecoder        ProtoContext        Application Handler
 *          |                      |                      |
 *          | handleStreamError()  |                      |
 *          | fireEvent(local)     |                      |
 *          |--------------------->|                      |
 *          |                      | Http2ResetEvent      |
 *          |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class Http2ResetEvent extends AbstractHttp2Event {
    public static final long CANCEL         = -1L;
    public static final long INTERNAL_ERROR = -2L;
    public static final long REFUSED        = -3L;
    private final long       errorCode;

    /**
     * Creates a stream-reset event.
     * @param streamId the stream ID being reset
     * @param errorCode the reset reason code
     */
    public Http2ResetEvent(long streamId, long errorCode) {
        this.streamId(streamId);
        this.errorCode = errorCode;
    }

    /**
     * Returns the protocol-level reset reason code.
     */
    public long errorCode() {
        return this.errorCode;
    }
}
