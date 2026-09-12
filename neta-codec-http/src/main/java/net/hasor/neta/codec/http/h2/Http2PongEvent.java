/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Network event representing an inbound HTTP/2 PING ACK.
 * <p>
 * This event is created only when the decoder processes a {@code PING} frame carrying the ACK flag,
 * exposing the peer's response to a previous local ping. A regular inbound {@code PING} does not
 * produce this event; instead, the decoder converts it into an outbound ACK frame.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | PING ACK frame         |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | fireEvent(remote)    |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2PongEvent       |
 *      |                        |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
public class Http2PongEvent extends AbstractHttp2Event {
    private final ByteBuf data;

    /**
     * Creates a PONG event with an empty 8-byte payload.
     * @param streamId the associated stream ID
     */
    public Http2PongEvent(long streamId) {
        this.streamId(streamId);
        this.data = ByteBuf.wrap(new byte[8]);
    }

    /**
     * Creates a PONG event with the specified 8-byte payload.
     * @param streamId the associated stream ID
     * @param data the PONG payload, which must be exactly 8 bytes and whose ownership
     * is transferred to this event
     */
    public Http2PongEvent(long streamId, ByteBuf data) {
        this.streamId(streamId);

        if (data == null) {
            this.data = ByteBuf.wrap(new byte[8]);
            return;
        }
        if (data.readableBytes() != 8) {
            throw new IllegalArgumentException("HTTP/2 pong payload must be exactly 8 bytes.");
        }
        this.data = data;
    }

    /**
     * Returns the PONG payload.
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
        return "Http2PongEvent{payloadLen=" + this.data.readableBytes() + '}';
    }
}
