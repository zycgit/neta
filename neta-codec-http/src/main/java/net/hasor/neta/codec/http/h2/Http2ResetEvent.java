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