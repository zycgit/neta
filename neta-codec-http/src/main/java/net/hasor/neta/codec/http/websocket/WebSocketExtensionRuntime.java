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
import net.hasor.neta.channel.ProtoContext;
/**
 * Runtime extension bound to one websocket connection.
 * <p>
 * Implementations decide whether they handle a given frame and may transform it
 * on the inbound or outbound side.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public interface WebSocketExtensionRuntime {
    /**
     * Return the negotiated extension result that created this runtime.
     * @return negotiated extension result
     */
    WebSocketExtensionResult negotiatedExtension();

    /**
     * Determine whether this runtime extension should decode the inbound frame.
     * @param frame inbound frame candidate
     * @return {@code true} when the frame should be decoded by this extension
     */
    boolean handlesInboundFrame(WebSocketFrame frame);

    /**
     * Determine whether this runtime extension claims the outbound frame.
     * @param frame outbound frame candidate
     * @return {@code true} when the frame should be encoded by this extension
     */
    boolean handlesOutboundFrame(WebSocketFrame frame);

    /**
     * Decode one inbound frame.
     * @param context protocol context for the current channel
     * @param frame original frame
     * @return decoded frame
     */
    WebSocketFrame decodeFrame(ProtoContext context, WebSocketFrame frame);

    /**
     * Encode one outbound frame.
     * @param context protocol context for the current channel
     * @param frame original frame
     * @return encoded frame
     */
    WebSocketFrame encodeFrame(ProtoContext context, WebSocketFrame frame);

    /**
     * Reset transient runtime state without releasing the extension instance itself.
     */
    default void reset() {
    }

    /**
     * Close the extension and release any internal resources.
     */
    default void close() {
        this.reset();
    }
}