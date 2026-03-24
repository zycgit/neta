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
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * User event that requests an outbound HTTP/2 PING frame.
 */
public class Http2PingEvent extends AbstractHttp2Event {
    private final ByteBuf data;

    public Http2PingEvent(int streamId) {
        this.streamId(streamId);
        this.data = ByteBuf.wrap(new byte[8]);
    }

    public Http2PingEvent(int streamId, ByteBuf data) {
        this.streamId(streamId);

        if (data == null) {
            this.data = ByteBuf.wrap(new byte[8]);
            return;
        }
        if (data.readableBytes() != 8) {
            throw new IllegalArgumentException("HTTP/2 ping payload must be exactly 8 bytes.");
        }
        this.data = data.retain();
    }

    public ByteBuf getData() {
        return this.data;
    }

    @Override
    protected void doRelease() {
        this.data.release();
    }

    @Override
    public String toString() {
        return "Http2PingEvent{payloadLen=" + this.data.readableBytes() + '}';
    }
}