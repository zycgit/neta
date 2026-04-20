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