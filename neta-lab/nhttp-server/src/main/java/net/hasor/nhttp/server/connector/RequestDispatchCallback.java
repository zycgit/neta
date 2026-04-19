/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.connector;

import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeEvent;

/**
 * Callback interface from the Connector layer to the Container layer.
 *
 * <p>All methods are invoked on the <b>neta IO thread</b>. Implementations must return quickly
 * and must not perform any blocking I/O or heavy computation directly. Thread switching into the
 * worker pool is the responsibility of the implementation (typically {@code RequestManager}).</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RequestDispatchCallback {

    /**
     * Called when the HTTP request line <em>and all request headers</em> have been fully received.
     * The body arrives asynchronously via {@code bodyChannel} after this call returns.
     *
     * @param context        neta protocol context for this stream / connection
     * @param requestLine    HTTP request line (method, URI, protocol version)
     * @param requestHeaders all HTTP request headers (combined from one or more header blocks)
     * @param bodyChannel    streaming channel for the request body; the IO thread will push
     *                       subsequent {@code HttpContent} chunks into it
     * @param responseSink   protocol-aware response writer, pre-configured for this stream
     * @param channel        underlying network channel
     * @param secure         {@code true} if the connection uses TLS
     */
    void onHttpRequest(ProtoContext context, HttpRequest requestLine, HttpHeaders requestHeaders, //
            BodyChannel bodyChannel, ResponseSink responseSink, //
            NetChannel channel, boolean secure);

    /**
     * Called when a WebSocket handshake has completed.
     *
     * @param context neta protocol context
     * @param event   handshake event containing the upgrade request/response details
     * @param channel underlying network channel
     * @param secure  {@code true} if the connection uses TLS
     */
    void onWebSocketOpen(ProtoContext context, WebSocketHandshakeEvent event, //
            NetChannel channel, boolean secure);

    /**
     * Called when a new TCP connection has been established.
     * Invoked after TLS (if any) but before the first request arrives.
     *
     * @param channel the newly connected channel
     */
    void onConnectionOpen(NetChannel channel);

    /**
     * Called when a TCP connection has been closed (either by the client or the server).
     *
     * @param channel the closed channel
     */
    void onConnectionClose(NetChannel channel);
}
