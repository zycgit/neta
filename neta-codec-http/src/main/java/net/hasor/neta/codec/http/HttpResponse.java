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
 * In the current object model, the response start line is separated from the header section and body section. As a result, a decoded response emits
 * {@link HttpResponse} first, followed by zero or more {@link HttpHeaders} objects, one {@link LastHttpHeaders}, zero or more {@link HttpContent} objects,
 * optional {@link TrailerHttpHeaders}, and, when the message has an explicit end boundary, a {@link LastHttpContent}.
 * Responses delimited by connection close end when the connection closes.
 * <pre>
 *   status-line = HTTP-version SP status-code SP reason-phrase CRLF
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface HttpResponse extends HttpObject {
    /**
     * Returns the protocol version from the status line.
     * @return protocol version
     */
    HttpVersion protocolVersion();

    /**
     * Sets the protocol version in the status line.
     * @param version protocol version
     * @return this response object
     */
    HttpResponse protocolVersion(HttpVersion version);

    /**
     * Returns the response status from the status line.
     * @return response status
     */
    HttpStatus status();

    /**
     * Sets the response status in the status line.
     * @param status response status
     * @return this response object
     */
    HttpResponse status(HttpStatus status);

    /**
     * Returns the textual form of the status code.
     * @return status code text
     */
    String statusText();

    /**
     * Returns the reason phrase.
     * @return reason phrase
     */
    String reasonText();

    /**
     * Sets the reason phrase.
     * @param reason reason phrase
     * @return this response object
     */
    HttpResponse reasonText(String reason);
}
