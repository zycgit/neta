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
 * Server-side WebSocket aggregation duplexer.
 * <p>
 * Entry assumption: the HTTP layer has already switched into transparent mode.
 * Inbound data arrives as {@link net.hasor.neta.codec.http.HttpByteBuf}, and
 * outbound data is emitted back as {@link net.hasor.neta.codec.http.HttpByteBuf}.
 */
public class WebSocketServerAggregatorDuplexe extends AbstractWebSocketAggregatorDuplexe {
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 65536;

    public WebSocketServerAggregatorDuplexe() {
        this(WebSocketVersion.V13, DEFAULT_MAX_MESSAGE_SIZE);
    }

    public WebSocketServerAggregatorDuplexe(WebSocketVersion version) {
        this(version, DEFAULT_MAX_MESSAGE_SIZE);
    }

    public WebSocketServerAggregatorDuplexe(WebSocketVersion version, int maxMessageSize) {
        super(version, false, maxMessageSize);
    }
}