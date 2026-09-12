/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when a protocol error can be clearly scoped to a single stream.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class HttpProtocolStreamException extends HttpProtocolException {
    /**
     * Create a stream-level protocol exception.
     * @param streamId stream identifier
     * @param errorCode protocol error code
     * @param message exception description
     */
    public HttpProtocolStreamException(long streamId, long errorCode, String message) {
        super(errorCode, message);
        this.setStreamId(streamId);
    }

    /**
     * Create a stream-level protocol exception with a root cause.
     * @param streamId stream identifier
     * @param errorCode protocol error code
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolStreamException(long streamId, long errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.setStreamId(streamId);
    }
}
