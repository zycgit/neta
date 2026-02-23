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
 * Represents an HTTP request message as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230, Section 3.1.1</a>.
 * <pre>
 *   request-line = method SP request-target SP HTTP-version CRLF
 * </pre>
 */
public interface HttpRequest extends HttpMessage {
    /** Returns the HTTP method of this request (e.g., GET, POST). */
    HttpMethod method();

    /** Sets the HTTP method of this request. */
    HttpRequest setMethod(HttpMethod method);

    /** Returns the URI (request-target) of this request. */
    String uri();

    /** Sets the URI (request-target) of this request. */
    HttpRequest setUri(String uri);
}
