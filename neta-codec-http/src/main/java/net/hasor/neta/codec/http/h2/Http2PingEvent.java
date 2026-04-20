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
 * Network event requesting an outbound HTTP/2 PING frame.
 * <p>
 * This event is usually initiated by the application or an upper-layer protocol. It is consumed by
 * {@link Http2ObjectEncoder#onEvent} and encoded into an outbound {@code PING} frame. Once the
 * remote {@code PING ACK} arrives, the decoder side publishes the result as an
 * {@link Http2PongEvent} instead of feeding another {@code Http2PingEvent} back into the pipeline.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 *   Application Handler   ProtoContext         Http2ObjectEncoder       Remote peer
 *          |                  |                      |                      |
 *          | fireEvent(...)   |                      |                      |
 *          |----------------->|                      |                      |
 *          |                  | onEvent(PING)        |                      |
 *          |                  |--------------------->|                      |
 *          |                  |                      | sendPing()           |
 *          |                  |                      | queueControlFrame()  |
 *          |                  |                      |--------------------->|
 *          |                  |                      |      PING frame      |
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2PingEvent extends AbstractHttp2Event {
    private final ByteBuf data;

    /**
     * Creates a PING event with an empty 8-byte payload.
     * @param streamId the associated stream ID
     */
    public Http2PingEvent(long streamId) {
        this.streamId(streamId);
        this.data = ByteBuf.wrap(new byte[8]);
    }

    /**
     * Creates a PING event with the specified 8-byte payload.
     * @param streamId the associated stream ID
     * @param data the PING payload, which must be exactly 8 bytes and whose ownership
     * is transferred to this event
     */
    public Http2PingEvent(long streamId, ByteBuf data) {
        this.streamId(streamId);

        if (data == null) {
            this.data = ByteBuf.wrap(new byte[8]);
            return;
        }
        if (data.readableBytes() != 8) {
            throw new IllegalArgumentException("HTTP/2 ping payload must be exactly 8 bytes.");
        }
        this.data = data;
    }

    /**
     * Returns the PING payload.
     */
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