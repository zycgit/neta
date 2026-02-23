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
 * Thrown when an HTTP message is syntactically malformed and cannot be parsed.
 * <p>Maps to {@link HttpStatus#BAD_REQUEST 400 Bad Request}.
 * <p>Typical causes:
 * <ul>
 *   <li>Invalid request-line or status-line format</li>
 *   <li>Malformed header field (missing colon, empty name)</li>
 *   <li>Invalid Content-Length value</li>
 *   <li>Invalid chunked transfer encoding format</li>
 * </ul>
 * @see HttpProtocolException
 */
public class HttpBadRequestException extends HttpProtocolException {
    public HttpBadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    public HttpBadRequestException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}