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
import net.hasor.neta.codec.http.AbstractHttpEvent;
/**
 * Network event published after a close control frame is received.
 * <p>
 * This event is produced by the WebSocket inbound handler after the inbound
 * CLOSE frame has been parsed into its status code and reason, and before the
 * close reply is written back. It notifies upper layers that the peer has
 * already initiated shutdown.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Peer sends a CLOSE frame
 *   Remote peer          WebSocketInboundHandler              ProtoContext              Application listener
 *        |                          |                                |                            |
 *        | CLOSE Frame              |                                |                            |
 *        |------------------------->|                                |                            |
 *        |                          | handleClose(...)               |                            |
 *        |                          | parse statusCode/reason        |                            |
 *        |                          | new WebSocketCloseEvent(...)   |                            |
 *        |                          | fireEvent(...)                 |                            |
 *        |                          |------------------------------->|                            |
 *        |                          |                                | WebSocketCloseEvent        |
 *        |                          |                                |--------------------------->|
 *        |                          | sendData(CLOSE reply) / close  |                            |
 *        |                          |------------------------------->|                            |
 * </pre>
 * <p>
 * It exposes the close status code and an optional reason text.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public class WebSocketCloseEvent extends AbstractHttpEvent {
    private final int    statusCode;
    private final String reason;

    /**
     * Create a close event.
     * @param statusCode close status code
     * @param reason close reason text
     */
    public WebSocketCloseEvent(int statusCode, String reason) {
        this.statusCode = statusCode;
        this.reason = reason;
    }

    /**
     * Return the close status code.
     */
    public int statusCode() {
        return this.statusCode;
    }

    /**
     * Return the close reason text.
     */
    public String reason() {
        return this.reason;
    }

    /**
     * Return the compact summary string of the event.
     */
    @Override
    public String toString() {
        return "WebSocketCloseEvent{code=" + this.statusCode + (this.reason != null ? ", reason='" + this.reason + '\'' : "") + '}';
    }
}