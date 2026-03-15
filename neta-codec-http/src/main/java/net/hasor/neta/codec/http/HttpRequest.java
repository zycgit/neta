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
 * Represents only the request line of an HTTP request.
 * <p>
 * In this object model the request start line is separated from the header and body sections.
 * A decoded request therefore begins with {@link HttpRequest}, followed by zero or more
 * {@link HttpHeaders}, one {@link LastHttpHeaders}, zero or more {@link HttpContent}, optional
 * {@link TrailerHttpHeaders}, and one {@link LastHttpContent}.
 * <pre>
 *   request-line = method SP request-target SP HTTP-version CRLF
 * </pre>
 */
public interface HttpRequest extends HttpObject {
    /** Returns the protocol version carried by this request line. */
    HttpVersion protocolVersion();

    /** Sets the protocol version carried by this aggregated request. */
    HttpRequest protocolVersion(HttpVersion version);

    /** Returns the request method carried by this request line. */
    HttpMethod method();

    /** Sets the request method carried by this aggregated request. */
    HttpRequest method(HttpMethod method);

    /** Returns the request target carried by this request line. */
    String uri();

    /** Sets the request target carried by this request line. */
    HttpRequest uri(String uri);
}
