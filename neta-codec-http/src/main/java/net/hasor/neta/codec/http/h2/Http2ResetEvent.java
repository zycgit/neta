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
package net.hasor.neta.codec.http.h2;

/**
 * Event describing an HTTP/2 stream reset.
 * <p>
 * The protocol layer publishes this event both when a remote peer sends an
 * inbound {@code RST_STREAM} frame and when the local endpoint detects a
 * stream-scoped protocol error and resets the stream itself. This keeps stream
 * lifecycle management inside the protocol layer while still exposing the
 * outcome to application handlers.
 */
public class Http2ResetEvent extends AbstractHttp2Event {
    public static final long CANCEL         = -1L;
    public static final long INTERNAL_ERROR = -2L;
    public static final long REFUSED        = -3L;

    private final long errorCode;

    public Http2ResetEvent(int streamId, long errorCode) {
        this.streamId(streamId);
        this.errorCode = errorCode;
    }

    /** Returns the protocol-specific reset reason code. */
    public long errorCode() {
        return this.errorCode;
    }
}