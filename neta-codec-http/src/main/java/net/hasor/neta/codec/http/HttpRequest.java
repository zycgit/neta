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
 * In the current object model, the request start line is separated from the header section and body section. As a result, a decoded request emits
 * {@link HttpRequest} first, followed by zero or more {@link HttpHeaders} objects, one {@link LastHttpHeaders}, zero or more {@link HttpContent} objects,
 * optional {@link TrailerHttpHeaders}, and one {@link LastHttpContent}.
 * <pre>
 *   request-line = method SP request-target SP HTTP-version CRLF
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface HttpRequest extends HttpObject {
    /**
     * Returns the protocol version from the request line.
     * @return protocol version
     */
    HttpVersion protocolVersion();

    /**
     * Sets the protocol version in the request line.
     * @param version protocol version
     * @return this request object
     */
    HttpRequest protocolVersion(HttpVersion version);

    /**
     * Returns the request method from the request line.
     * @return request method
     */
    HttpMethod method();

    /**
     * Sets the request method in the request line.
     * @param method request method
     * @return this request object
     */
    HttpRequest method(HttpMethod method);

    /**
     * Returns the request target from the request line.
     * @return request target
     */
    String uri();

    /**
     * Sets the request target in the request line.
     * @param uri request target
     * @return this request object
     */
    HttpRequest uri(String uri);
}
