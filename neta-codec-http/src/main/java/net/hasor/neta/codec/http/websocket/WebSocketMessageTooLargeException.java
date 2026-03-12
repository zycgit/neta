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
package net.hasor.neta.codec.http.websocket;
/**
 * Thrown when a reassembled WebSocket message exceeds the configured
 * maximum size in {@link WebSocketFrameAggregator}.
 */
public class WebSocketMessageTooLargeException extends RuntimeException {
    private final long maxSize;
    private final long actualSize;

    public WebSocketMessageTooLargeException(String message, long maxSize, long actualSize) {
        super(message);
        this.maxSize = maxSize;
        this.actualSize = actualSize;
    }

    /** Returns the configured maximum message size. */
    public long maxSize() {
        return this.maxSize;
    }

    /** Returns the actual message size that triggered this exception. */
    public long actualSize() {
        return this.actualSize;
    }
}