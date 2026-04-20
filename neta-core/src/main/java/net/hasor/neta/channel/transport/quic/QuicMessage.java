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
package net.hasor.neta.channel.transport.quic;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.cobble.ref.RecycleObjectPool.ObjHandler;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Reassembled QUIC stream payload delivered on the connection pipeline when {@link QuicChannelMode#CHANNEL} is enabled.
 * <p>
 * Ownership follows the standard Neta rule: whoever consumes a received {@link QuicMessage}
 * must call {@link #release()} after finishing with it. For outbound messages submitted through
 * {@code NetChannel.sendData(...)}, the framework releases the message automatically after the
 * send completes or fails.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicMessage implements Release {
    private static final int                     RECYCLE_INDEX   = RecycleObjectPool.registerType();
    private static final ObjHandler<QuicMessage> RECYCLE_HANDLER = //
            new ObjHandler<QuicMessage>() {
                @Override
                public QuicMessage create() {
                    return new QuicMessage();
                }

                @Override
                public void free(QuicMessage tar) {
                    RecycleObjectPool.free(RECYCLE_INDEX, tar);
                }
            };

    private long    streamId;
    private ByteBuf byteBuf;
    private boolean fin;

    private QuicMessage() {
    }

    private void init(long streamId, ByteBuf byteBuf, boolean fin) {
        this.streamId = streamId;
        this.byteBuf = byteBuf;
        this.fin = fin;
    }

    /**
     * Creates a non-terminal QUIC message.
     */
    public static QuicMessage of(long streamId, ByteBuf byteBuf) {
        return of(streamId, byteBuf, false);
    }

    /**
     * Creates a QUIC message with explicit FIN metadata.
     */
    public static QuicMessage of(long streamId, ByteBuf byteBuf, boolean fin) {
        QuicMessage message = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        message.init(streamId, byteBuf, fin);
        return message;
    }

    /**
     * Returns the stream identifier.
     */
    public long streamId() {
        return this.streamId;
    }

    /**
     * Returns the message body.
     */
    public ByteBuf content() {
        return this.byteBuf;
    }

    /**
     * Alias of {@link #content()} for consistency with SCTP message APIs.
     */
    public ByteBuf getByteBuf() {
        return this.byteBuf;
    }

    /**
     * Returns whether this message carries the terminal FIN signal for the stream.
     */
    public boolean isFin() {
        return this.fin;
    }

    /**
     * Returns whether the associated stream is bidirectional.
     */
    public boolean isBidi() {
        return (this.streamId & 0x02) == 0;
    }

    /**
     * Returns whether the associated stream is unidirectional.
     */
    public boolean isUni() {
        return (this.streamId & 0x02) != 0;
    }

    @Override
    public void release() {
        if (this.byteBuf != null) {
            this.byteBuf.release();
            this.byteBuf = null;
        }
        this.streamId = 0L;
        this.fin = false;
        RECYCLE_HANDLER.free(this);
    }
}