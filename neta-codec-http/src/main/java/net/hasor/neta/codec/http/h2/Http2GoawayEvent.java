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

import java.util.Arrays;

/**
 * Event describing an HTTP/2 connection shutdown boundary.
 * <p>
 * The protocol layer publishes this event both when a remote peer sends
 * {@code GOAWAY} and when the local endpoint decides to terminate the
 * connection because of a connection-scoped protocol error. Application layers
 * can observe the shutdown boundary and reason about retry or cleanup without
 * taking over protocol-level connection management.
 */
public class Http2GoawayEvent extends AbstractHttp2Event {
    private final long   lastAcceptedId;
    private final long   errorCode;
    private final byte[] debugData;

    public Http2GoawayEvent(int streamId, long lastAcceptedId, long errorCode, byte[] debugData) {
        this.streamId(streamId);
        this.lastAcceptedId = lastAcceptedId;
        this.errorCode = errorCode;
        this.debugData = debugData == null ? new byte[0] : Arrays.copyOf(debugData, debugData.length);
    }

    /** Returns the last identifier the remote peer still accepts after GOAWAY. */
    public long lastAcceptedId() {
        return this.lastAcceptedId;
    }

    /** Returns the protocol-specific error code, or {@code 0} when absent. */
    public long errorCode() {
        return this.errorCode;
    }

    /** Returns optional debug payload, or an empty array when absent. */
    public byte[] debugData() {
        return Arrays.copyOf(this.debugData, this.debugData.length);
    }
}