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
 * Network event published when a close control frame is observed or generated.
 * <p>
 * Exposes the close status code and optional reason text at the message/event layer.
 */
public class WebSocketCloseEvent extends AbstractHttpEvent {
    private final int    statusCode;
    private final String reason;

    public WebSocketCloseEvent(int statusCode, String reason) {
        this.statusCode = statusCode;
        this.reason = reason;
    }

    public int statusCode() {
        return this.statusCode;
    }

    public String reason() {
        return this.reason;
    }

    @Override
    public String toString() {
        return "WebSocketCloseEvent{code=" + this.statusCode + (this.reason != null ? ", reason='" + this.reason + '\'' : "") + '}';
    }
}