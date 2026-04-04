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
package net.hasor.neta.codec.http.websocket.extension;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;

/**
 * Runtime view of one negotiated websocket extension on a connection.
 */
public interface WebSocketRuntimeExtension {
    WebSocketExtensionResult negotiatedExtension();

    boolean handlesInboundFrame(WebSocketFrame frame);

    boolean handlesOutboundFrame(WebSocketFrame frame);

    WebSocketFrame decodeFrame(ProtoContext context, WebSocketFrame frame);

    WebSocketFrame encodeFrame(ProtoContext context, WebSocketFrame frame);

    default void reset() {
    }

    default void close() {
        this.reset();
    }
}