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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.AbstractHttpEvent;

/**
 * Event used to request sending a websocket ping control frame.
 * <p>
 * This event is not produced automatically by inbound network frames. Instead,
 * it is actively published by business code or an upstream protocol handler,
 * then intercepted by
 * {@link WebSocketInboundHandler#onEvent(net.hasor.neta.channel.ProtoContext, net.hasor.neta.channel.SoEvent)}
 * or {@link WebSocketOutboundHandler#onEvent(net.hasor.neta.channel.ProtoContext, net.hasor.neta.channel.SoEvent)}
 * and converted into an actual ping control message.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Local side actively sends ping
 *   Business/Upstream Handler      ProtoContext         Inbound/Outbound Handler       WebSocket outbound flow
 *             |                        |                         |                              |
 *             | fireEvent(PING)        |                         |                              |
 *             |----------------------->|                         |                              |
 *             |                        | onEvent(...)            |                              |
 *             |                        |------------------------>|                              |
 *             |                        |                         | sendControlEventFrame(...)   |
 *             |                        |                         |----------------------------->|
 *             |                        | sendData(PING)          |                              |
 *             |                        |------------------------------------------------------->|
 * </pre>
 * <p>
 * The event may carry an optional ping payload.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public class PingWebSocketEvent extends AbstractHttpEvent {
    private final ByteBuf content;

    /**
     * Create a ping event with an empty payload.
     */
    public PingWebSocketEvent() {
        this(ByteBuf.EMPTY);
    }

    /**
     * Create a ping event with the specified payload.
     * @param content ping payload whose ownership is transferred to this event
     */
    public PingWebSocketEvent(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY : content;
    }

    /**
     * Return the ping event payload.
     */
    public ByteBuf content() {
        return this.content;
    }

    /**
     * Release the payload held by this event.
     */
    @Override
    protected void doRelease() {
        this.content.release();
    }

    /**
     * Return a compact summary string for the event.
     */
    @Override
    public String toString() {
        return "PingWebSocketEvent{payloadLen=" + this.content.readableBytes() + '}';
    }
}