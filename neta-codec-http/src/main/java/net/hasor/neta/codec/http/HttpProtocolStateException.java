/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when the HTTP codec observes an illegal protocol state transition, timing violation, or a mode/state mismatch.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class HttpProtocolStateException extends HttpProtocolException {
    /**
     * Create a protocol state exception.
     * @param message exception description
     */
    public HttpProtocolStateException(String message) {
        super(message);
    }

    /**
     * Create a protocol state exception bound to the specified stream.
     * @param streamId stream identifier
     * @param message exception description
     */
    public HttpProtocolStateException(long streamId, String message) {
        super(message);
        this.setStreamId(streamId);
    }
}
