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
 * Marker interface for all HTTP objects that flow through the HTTP codec pipeline.
 * <p>
 * An HTTP message is decoded into a sequence of {@link HttpObject}s:
 * <ol>
 *   <li>{@link HttpRequest} or {@link HttpResponse} - the initial line and headers</li>
 *   <li>Zero or more {@link HttpContent} - body chunks</li>
 *   <li>{@link LastHttpContent} - marks the end of the message</li>
 * </ol>
 * <p>
 * HTTP/2 transparency: objects decoded from HTTP/2 carry the originating stream ID
 * via {@link #streamId()}, enabling protocol-agnostic proxy and routing logic.
 * HTTP/1.x objects always return {@code 0}.
 */
public interface HttpObject {
    /**
     * Returns the HTTP/2 stream ID associated with this object,
     * or {@code 0} if not applicable (HTTP/1.x or connection-level).
     */
    int streamId();

    /**
     * Sets the HTTP/2 stream ID. Returns {@code this} for chaining.
     * The default implementation is a no-op (HTTP/1.x objects ignore the call).
     */
    HttpObject streamId(int streamId);
}