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
 * Thrown when an HTTP codec observes an invalid protocol state transition,
 * sequencing violation, or mode/state mismatch.
 */
public class HttpProtocolStateException extends HttpProtocolException {
    public HttpProtocolStateException(String message) {
        super(message);
    }

    public HttpProtocolStateException(int streamId, String message) {
        super(message);
        this.setStreamId(streamId);
    }
}