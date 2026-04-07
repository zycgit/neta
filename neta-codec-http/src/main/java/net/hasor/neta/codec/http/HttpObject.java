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
 * Marks all HTTP objects that flow through the HTTP codec pipeline.
 * <p>
 * A complete message is typically represented as an ordered sequence of {@link HttpObject} instances:
 * <ol>
 *   <li>a {@link HttpRequest} or {@link HttpResponse} carrying the start line</li>
 *   <li>zero or more {@link HttpHeaders} header blocks</li>
 *   <li>a {@link LastHttpHeaders} object that closes the initial header section</li>
 *   <li>zero or more {@link HttpContent} body chunks</li>
 *   <li>for chunked messages, zero or more {@link TrailerHttpHeaders} objects after the body</li>
 *   <li>for messages with an explicit end boundary, a {@link LastHttpContent} object that closes the content section</li>
 * </ol>
 * <p>
 * Responses delimited by connection close do not emit a terminal object after the last content chunk; the message ends when the connection closes.
 * <p>
 * Aggregated forms such as {@link FullHttpRequest} and {@link FullHttpResponse} fold the final header marker and final content marker into a single object for easier use.
 * <p>
 * In HTTP/2 pass-through scenarios, objects decoded from HTTP/2 carry their source stream identifier through {@link #streamId()} so protocol-agnostic proxying and routing can preserve stream affinity.
 * HTTP/1.x objects usually return {@code 0}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface HttpObject extends Release {
    /**
     * Returns the HTTP/2 stream identifier associated with this object, or {@code 0} when no stream is attached.
     * @return stream identifier
     */
    int streamId();

    /**
     * Associates an HTTP/2 stream identifier with this object and returns the current object for chaining.
     * @param streamId stream identifier
     * @return this object
     */
    HttpObject streamId(int streamId);

    /**
     * Returns whether this object belongs to a malformed HTTP message.
     * @return whether this is a bad message object
     */
    boolean isBad();

    /**
     * Returns the parse failure reason when {@link #isBad()} is true; otherwise returns {@code null}.
     * @return failure reason
     */
    String badReason();

    /**
     * Marks this object as a bad message object and returns the current object for chaining.
     * @param reason failure reason
     * @return this object
     */
    HttpObject markBad(String reason);
}