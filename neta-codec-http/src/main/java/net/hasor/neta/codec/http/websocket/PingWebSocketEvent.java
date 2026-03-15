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

public class PingWebSocketEvent extends AbstractWebSocketEvent {
    private final ByteBuf content;

    public PingWebSocketEvent() {
        this(ByteBuf.EMPTY);
    }

    public PingWebSocketEvent(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY.retain() : content.retain();
    }

    public ByteBuf content() {
        return this.content;
    }

    @Override
    protected void doRelease() {
        this.content.release();
    }

    @Override
    public String toString() {
        return "PingWebSocketEvent{payloadLen=" + this.content.readableBytes() + '}';
    }
}