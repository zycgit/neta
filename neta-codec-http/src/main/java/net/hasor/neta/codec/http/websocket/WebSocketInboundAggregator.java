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
 * Direction-explicit inbound alias for {@link WebSocketFrameAggregator}.
 * <p>
 * Use this class when the pipeline is assembled as a duplex unit and you want the role to
 * be obvious from the type name. Behavior is identical to {@link WebSocketFrameAggregator}:
 * inbound frames are reassembled into message-level objects, and control frames are handled
 * according to the same rules.
 * <p>
 * Typical use is inside a duplex WebSocket stack:
 * <pre>
 *   HttpByteBuf
 *      -> WebSocketFrameDecoder
 *      -> WebSocketInboundAggregator
 *      -> WebSocketMessage
 * </pre>
 * <p>
 * This alias is mainly paired with {@link WebSocketOutboundAggregator} and
 * {@link AbstractWebSocketDuplexeAggregator}.
 */
public class WebSocketInboundAggregator extends WebSocketFrameAggregator {
    public WebSocketInboundAggregator() {
        super();
    }

    public WebSocketInboundAggregator(int maxMessageSize) {
        super(maxMessageSize);
    }
}