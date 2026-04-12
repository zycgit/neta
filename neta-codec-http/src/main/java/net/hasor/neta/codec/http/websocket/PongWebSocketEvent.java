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
 * Event representing a received pong control frame or a request to send pong actively.
 * <p>
 * This event has two sources. One source is when the WebSocket inbound handler
 * consumes a network-side PONG frame and creates the event for publication to
 * the receive side. The other source is business code actively calling
 * fireEvent, after which handlers intercept it and convert it into an outbound
 * pong control message.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Branch A: receiving a network-side PONG frame
 *   Remote peer         WebSocketInboundHandler            ProtoContext             Application listener
 *        |                        |                              |                            |
 *        | PONG Frame             |                              |                            |
 *        |----------------------->|                              |                            |
 *        |                        | handlePong(...)              |                            |
 *        |                        | new PongWebSocketEvent(...)  |                            |
 *        |                        | fireEvent(...)               |                            |
 *        |                        |----------------------------->|                            |
 *        |                        |                              | PongWebSocketEvent         |
 *        |                        |                              |--------------------------->|
 * </pre><pre>
 * Branch B: actively sending PONG locally
 *   Business/Upstream Handler      ProtoContext         Inbound/Outbound Handler       WebSocket outbound flow
 *             |                        |                         |                              |
 *             | fireEvent(PONG)        |                         |                              |
 *             |----------------------->|                         |                              |
 *             |                        | onEvent(...)            |                              |
 *             |                        |------------------------>|                              |
 *             |                        |                         | sendControlEventFrame(...)   |
 *             |                        |                         |----------------------------->|
 *             |                        | sendData(PONG)          |                              |
 *             |                        |------------------------------------------------------->|
 * </pre>
 * <p>
 * The event may carry an optional pong payload.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public class PongWebSocketEvent extends AbstractHttpEvent {
    private final ByteBuf content;

    /**
     * Create a pong event with an empty payload.
     */
    public PongWebSocketEvent() {
        this(ByteBuf.EMPTY);
    }

    /**
     * Create a pong event with the specified payload.
     * @param content pong payload whose ownership is transferred to this event
     */
    public PongWebSocketEvent(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY : content;
    }

    /**
     * Return the pong event payload.
     */
    public ByteBuf content() {
        return this.content;
    }

    /**
     * Release the payload resource held by this event.
     */
    @Override
    protected void doRelease() {
        this.content.release();
    }

    /**
     * Return the compact summary string of the event.
     */
    @Override
    public String toString() {
        return "PongWebSocketEvent{payloadLen=" + this.content.readableBytes() + '}';
    }
}