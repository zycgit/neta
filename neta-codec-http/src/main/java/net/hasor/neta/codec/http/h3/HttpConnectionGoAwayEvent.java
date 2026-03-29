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
package net.hasor.neta.codec.http.h3;
import java.util.Arrays;
import net.hasor.neta.codec.http.HttpEvent;

/**
 * Connection-level graceful-shutdown signal published when the remote peer sends GOAWAY.
 * <p>
 * The event is protocol-agnostic across HTTP/2 and HTTP/3. The meaning of
 * {@link #lastAcceptedId()} depends on the transport:
 * <ul>
 *   <li>HTTP/2: last accepted stream ID</li>
 *   <li>HTTP/3: last accepted request or push identifier encoded by GOAWAY</li>
 * </ul>
 */
public class HttpConnectionGoAwayEvent implements HttpEvent {
    private final long   lastAcceptedId;
    private final long   errorCode;
    private final byte[] debugData;

    public HttpConnectionGoAwayEvent(long lastAcceptedId, long errorCode, byte[] debugData) {
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