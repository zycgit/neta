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
 * Marks the end of an HTTP message body.
 * <p>
 * In chunked transfer encoding (RFC 7230 §4.1), this corresponds to the
 * last-chunk and optional trailer section. For fixed-length bodies, this
 * is emitted after all content bytes have been received.
 * <p>
 * For chunked encoding:
 * <pre>
 *   last-chunk     = 1*("0") [ chunk-ext ] CRLF
 *   trailer-part   = *( header-field CRLF )
 *   CRLF
 * </pre>
 */
public interface LastHttpContent extends HttpContent {
    /**
     * Returns the trailing headers that follow the last chunk in
     * chunked transfer encoding. Returns empty headers if no trailing
     * headers are present.
     */
    HttpHeaders trailerHeaders();
}
