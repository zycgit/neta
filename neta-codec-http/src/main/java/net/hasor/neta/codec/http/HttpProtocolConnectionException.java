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
package net.hasor.neta.codec.http;

/**
 * Thrown when a protocol error affects the entire connection rather than a single stream.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class HttpProtocolConnectionException extends HttpProtocolException {
    /**
     * Create a connection-level protocol exception.
     * @param errorCode protocol error code
     * @param message exception description
     */
    public HttpProtocolConnectionException(long errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Create a connection-level protocol exception associated with a stream identifier.
     * @param streamId stream identifier
     * @param errorCode protocol error code
     * @param message exception description
     */
    public HttpProtocolConnectionException(int streamId, long errorCode, String message) {
        super(errorCode, message);
        this.setStreamId(streamId);
    }

    /**
     * Create a connection-level protocol exception with a root cause.
     * @param errorCode protocol error code
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolConnectionException(long errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Create a connection-level protocol exception with a stream identifier and root cause.
     * @param streamId stream identifier
     * @param errorCode protocol error code
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolConnectionException(int streamId, long errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.setStreamId(streamId);
    }
}