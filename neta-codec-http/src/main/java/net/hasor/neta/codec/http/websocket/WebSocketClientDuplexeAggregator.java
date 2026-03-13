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
 * Client-side duplex WebSocket transport stack for use after the opening handshake.
 * <p>
 * This class bundles inbound frame decoding and aggregation together with outbound
 * message-to-frame conversion and serialization. It is the client-side, post-handshake
 * shortcut for the following four handlers: {@link WebSocketFrameDecoder},
 * {@link WebSocketInboundAggregator}, {@link WebSocketOutboundAggregator}, and
 * {@link WebSocketFrameEncoder}.
 * <p>
 * Typical pipeline placement:
 * <pre>
 *   ctx.addLast("http", new HttpClientDuplexe());
 *   ctx.addLast("ws-handshake", new WebSocketClientDuplexer(WebSocketVersion.V13));
 *   ctx.addLast("ws-io", new WebSocketClientDuplexeAggregator(WebSocketVersion.V13));
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   inbound:  HttpByteBuf -> WebSocketClientDuplexeAggregator -> WebSocketMessage
 *   outbound: WebSocketMessage -> WebSocketClientDuplexeAggregator -> HttpByteBuf
 * </pre>
 * <p>
 * Entry assumption: the HTTP layer has already switched into transparent mode.
 * In RFC 6455 mode, outbound client frames are masked automatically as required for
 * client-to-server traffic.
 */
public class WebSocketClientDuplexeAggregator extends AbstractWebSocketDuplexeAggregator {
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 65536;

    public WebSocketClientDuplexeAggregator() {
        this(WebSocketVersion.V13, DEFAULT_MAX_MESSAGE_SIZE);
    }

    public WebSocketClientDuplexeAggregator(WebSocketVersion version) {
        this(version, DEFAULT_MAX_MESSAGE_SIZE);
    }

    public WebSocketClientDuplexeAggregator(WebSocketVersion version, int maxMessageSize) {
        super(version, true, maxMessageSize);
    }
}