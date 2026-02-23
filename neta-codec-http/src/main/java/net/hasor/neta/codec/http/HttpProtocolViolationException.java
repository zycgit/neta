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
 * Thrown when a wire-level protocol violation is detected during HTTP/2 or HTTP/3
 * frame processing, HPACK/QPACK header compression, or connection management.
 * <p>
 * Unlike {@link HttpBadRequestException} which covers application-level message
 * malformation (bad request-line, invalid headers), this exception represents
 * lower-level protocol violations that typically result in connection termination
 * rather than a simple error response.
 * <p>Typical causes:
 * <ul>
 *   <li>Invalid HTTP/2 connection preface</li>
 *   <li>Frame size exceeds SETTINGS_MAX_FRAME_SIZE</li>
 *   <li>HPACK/QPACK decoding errors (truncated integer, invalid index)</li>
 *   <li>Missing required pseudo-headers (:method, :path)</li>
 *   <li>Invalid frame structure (wrong PING size, bad WINDOW_UPDATE)</li>
 *   <li>CONTINUATION frame without preceding HEADERS</li>
 * </ul>
 * <p>The default status is {@link HttpStatus#BAD_REQUEST 400}, but callers can
 * specify a more specific status (e.g., {@link HttpStatus#INTERNAL_SERVER_ERROR 500}
 * for server-side decode failures).
 * @see HttpProtocolException
 */
public class HttpProtocolViolationException extends HttpProtocolException {
    /** Creates a violation exception with the default status {@link HttpStatus#BAD_REQUEST 400}. */
    public HttpProtocolViolationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    /** Creates a violation exception with a specific HTTP status. */
    public HttpProtocolViolationException(HttpStatus status, String message) {
        super(status, message);
    }

    /** Creates a violation exception with a specific HTTP status and cause. */
    public HttpProtocolViolationException(HttpStatus status, String message, Throwable cause) {
        super(status, message, cause);
    }
}