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
 * Thrown when HTTP header fields exceed the configured maximum header size.
 * <p>Maps to {@link HttpStatus#REQUEST_HEADER_FIELDS_TOO_LARGE 431 Request Header Fields Too Large}.
 * @see HttpSizeLimitException
 */
public class HttpHeaderTooLargeException extends HttpSizeLimitException {
    public HttpHeaderTooLargeException(String message) {
        super(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, message);
    }

    public HttpHeaderTooLargeException(String message, long limit, long actual) {
        super(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, message, limit, actual);
    }
}