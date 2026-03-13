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
import net.hasor.cobble.function.Release;

/**
 * Marker interface for all HTTP objects that flow through the HTTP codec pipeline.
 * <p>
 * A complete message is represented as an ordered sequence of {@link HttpObject}s:
 * <ol>
 *   <li>{@link HttpRequest} or {@link HttpResponse} for the start line</li>
 *   <li>Zero or more {@link HttpHeaders} header blocks</li>
 *   <li>One {@link LastHttpHeaders} marker that closes the header section</li>
 *   <li>Zero or more {@link HttpContent} body chunks</li>
 *   <li>Zero or more {@link TrailerHttpHeaders} blocks after the body for chunked messages</li>
 *   <li>One {@link LastHttpContent} marker that closes the body section</li>
 * </ol>
 * <p>
 * Aggregated forms such as {@link FullHttpRequest} and {@link FullHttpResponse} collapse the
 * final header marker and final content marker into a single object for convenience.
 * <p>
 * HTTP/2 transparency: objects decoded from HTTP/2 carry the originating stream ID via
 * {@link #streamId()}, enabling protocol-agnostic proxy and routing logic. HTTP/1.x objects
 * typically return {@code 0}.
 */
public interface HttpObject extends Release {
    /** Returns the HTTP/2 stream ID associated with this object, or {@code 0} when none exists. */
    int streamId();

    /** Associates this object with an HTTP/2 stream ID and returns {@code this} for chaining. */
    HttpObject streamId(int streamId);

    /** Returns whether this object belongs to a syntactically malformed HTTP message. */
    default boolean isBad() {
        return false;
    }

    /** Returns the parse failure reason when {@link #isBad()} is true, otherwise {@code null}. */
    default String badReason() {
        return null;
    }

    /** Marks this object as malformed and returns {@code this} for chaining. */
    default HttpObject markBad(String reason) {
        return this;
    }
}