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
package net.hasor.neta.codec.http3;

/**
 * HTTP/3 stream states for codec-level tracking.
 * <p>
 * HTTP/3 streams map to QUIC streams. Each request/response exchange
 * uses a separate bidirectional QUIC stream. This enum tracks the
 * HTTP-layer state of each stream.
 * @see Http3Stream
 */
public enum Http3StreamState {
    /** Stream is created but no frames have been sent or received. */
    IDLE,
    /** HEADERS frame has been sent/received; awaiting more data or trailers. */
    OPEN,
    /** The request/response exchange is complete (FIN received/sent). */
    HALF_CLOSED,
    /** Stream is fully closed. */
    CLOSED
}
