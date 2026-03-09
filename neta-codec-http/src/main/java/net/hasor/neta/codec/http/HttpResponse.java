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
 * Represents only the status line of an HTTP response.
 * <p>
 * In this object model the response start line is separated from the header and body sections.
 * A decoded response therefore begins with {@link HttpResponse}, followed by zero or more
 * {@link HttpHeaders}, one {@link LastHttpHeaders}, zero or more {@link HttpContent}, optional
 * {@link TrailerHttpHeaders}, and one {@link LastHttpContent}.
 * <pre>
 *   status-line = HTTP-version SP status-code SP reason-phrase CRLF
 * </pre>
 */
public interface HttpResponse extends HttpObject {
    /** Returns the protocol version carried by this status line. */
    HttpVersion protocolVersion();

    /** Returns the status carried by this status line. */
    HttpStatus status();

    String statusText();

    String reasonText();
}
