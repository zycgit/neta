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
 * Thrown when an HTTP request line or status line exceeds the configured maximum length.
 * <p>Corresponds to {@link HttpStatus#REQUEST_URI_TOO_LONG 414 Request-URI Too Long}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 * @see HttpSizeLimitException
 */
public class HttpInitialLineTooLongException extends HttpSizeLimitException {
    /**
     * Creates an initial-line-too-long exception.
     * @param message exception description
     */
    public HttpInitialLineTooLongException(String message) {
        super(HttpStatus.REQUEST_URI_TOO_LONG, message);
    }

    /**
     * Creates an initial-line-too-long exception.
     * @param message exception description
     * @param limit configured limit
     * @param actual actual size
     */
    public HttpInitialLineTooLongException(String message, long limit, long actual) {
        super(HttpStatus.REQUEST_URI_TOO_LONG, message, limit, actual);
    }
}