/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when the protocol decoder detects payload boundary overflow, an out-of-range value, or an invalid numeric constraint that makes the input unsafe to process.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class HttpProtocolOutOfBoundsException extends HttpProtocolException {
    /**
     * Create an out-of-bounds exception.
     * @param message exception description
     */
    public HttpProtocolOutOfBoundsException(String message) {
        super(message);
    }

    /**
     * Create an out-of-bounds exception bound to the specified stream.
     * @param streamId stream identifier
     * @param message exception description
     */
    public HttpProtocolOutOfBoundsException(long streamId, String message) {
        super(message);
        this.setStreamId(streamId);
    }
}
