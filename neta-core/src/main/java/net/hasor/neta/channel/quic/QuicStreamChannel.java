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
package net.hasor.neta.channel.quic;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Stream-level QUIC channel with its own pipeline, created and managed by the parent {@link QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel
 */
public class QuicStreamChannel extends NetChannel implements SoSubChannel {
    private final    long        streamId;
    private final    QuicChannel parent;
    private volatile long        maxDataSize;
    /** Last time (in milliseconds) data was sent or received on this stream. Used for stream-level idle timeout. */
    private volatile long        lastActivityTime;

    /** Creates a stream-level channel from an already-constructed async stream channel; called exclusively by {@link QuicChannel}. */
    QuicStreamChannel(long channelId, long streamId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer,//
            QuicStreamChannelAsync asyncChannel, SoContextService soContext, QuicChannel parent, long initMaxDataSize) {
        super(channelId, monitor, forListen, initializer, asyncChannel, soContext);
        this.streamId = streamId;
        this.parent = parent;
        this.maxDataSize = initMaxDataSize;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /** Returns the QUIC stream ID bound to this channel. */
    public long getStreamId() {
        return this.streamId;
    }

    /**
     * Returns the effective per-stream data limit; can only increase via {@link #sendMaxDataSize(long)} or peer's MAX_STREAM_DATA.
     */
    public long getMaxDataSize() {
        return this.maxDataSize;
    }

    /**
     * Updates the per-stream flow-control limit when the peer sends a MAX_STREAM_DATA frame; smaller values are silently ignored.
     */
    void updateMaxDataSize(long newMaxDataSize) {
        this.maxDataSize = Math.max(this.maxDataSize, newMaxDataSize);
    }

    /** Returns the last activity time (epoch ms) for idle timeout checking. Package-private. */
    long getLastActivityTime() {
        return this.lastActivityTime;
    }

    /** Updates the last activity timestamp. Called when data is sent or received on this stream. Package-private. */
    void touchActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /** Returns the parent connection-level {@link QuicChannel} that owns this stream. */
    @Override
    public QuicChannel getParent() {
        return this.parent;
    }

    /** Returns the {@link SslContext} from the parent QUIC connection, or null if SSL is disabled. */
    public SslContext getSslContext() {
        return this.parent.getSslContext();
    }

    /**
     * Returns true if this is a bidirectional stream (bit 1 of stream ID == 0, per RFC 9000 §2.1).
     */
    public boolean isBidi() {
        return (this.streamId & 0x02) == 0;
    }

    /**
     * Returns true if this is a unidirectional stream (bit 1 of stream ID == 1, per RFC 9000 §2.1).
     */
    public boolean isUni() {
        return (this.streamId & 0x02) != 0;
    }

    /**
     * Sends a RESET_STREAM frame (RFC 9000 §19.4) to abruptly terminate this stream with the given error code and final size.
     */
    public Future<QuicChannel> sendReset(long errorCode, long finalSize) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.RESET_STREAM);
            byte[] sidBytes = QuicVarInt.encode(this.streamId);
            byte[] errBytes = QuicVarInt.encode(errorCode);
            byte[] sizeBytes = QuicVarInt.encode(finalSize);
            int totalSize = typeBytes.length + sidBytes.length + errBytes.length + sizeBytes.length;
            byte[] frame = new byte[totalSize];
            int pos = 0;
            System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
            pos += typeBytes.length;
            System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
            pos += sidBytes.length;
            System.arraycopy(errBytes, 0, frame, pos, errBytes.length);
            pos += errBytes.length;
            System.arraycopy(sizeBytes, 0, frame, pos, sizeBytes.length);
            this.parent.asyncChannel().sendDataFrame(ByteBuf.wrap(frame), future);
        } catch (Throwable e) {
            future.failed(e);
        }
        return future;
    }

    /**
     * Sends a STOP_SENDING frame (RFC 9000 §19.5) asking the peer to stop sending data on this stream.
     */
    public Future<QuicChannel> sendStop(long errorCode) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.STOP_SENDING);
            byte[] sidBytes = QuicVarInt.encode(this.streamId);
            byte[] errBytes = QuicVarInt.encode(errorCode);
            int totalSize = typeBytes.length + sidBytes.length + errBytes.length;
            byte[] frame = new byte[totalSize];
            int pos = 0;
            System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
            pos += typeBytes.length;
            System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
            pos += sidBytes.length;
            System.arraycopy(errBytes, 0, frame, pos, errBytes.length);
            this.parent.asyncChannel().sendDataFrame(ByteBuf.wrap(frame), future);
        } catch (Throwable e) {
            future.failed(e);
        }
        return future;
    }

    /**
     * Sends raw bytes on this QUIC stream as a STREAM frame (RFC 9000 §19.8), bypassing the protocol pipeline.
     */
    public Future<QuicChannel> sendRawData(byte[] data) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        try {
            int type = QuicFrameType.STREAM_BASE | QuicFrameType.STREAM_LEN_BIT;
            byte[] typeBytes = QuicVarInt.encode(type);
            byte[] sidBytes = QuicVarInt.encode(this.streamId);
            byte[] lenBytes = QuicVarInt.encode(data.length);
            int totalSize = typeBytes.length + sidBytes.length + lenBytes.length + data.length;
            byte[] frame = new byte[totalSize];
            int pos = 0;
            System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
            pos += typeBytes.length;
            System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
            pos += sidBytes.length;
            System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
            pos += lenBytes.length;
            System.arraycopy(data, 0, frame, pos, data.length);
            this.parent.asyncChannel().sendDataFrame(ByteBuf.wrap(frame), future);
        } catch (Throwable e) {
            future.failed(e);
        }
        return future;
    }

    /**
     * Sends a MAX_STREAM_DATA frame (RFC 9000 §19.10) increasing this stream's receive-side flow-control limit; new value must be ≥ current.
     */
    public Future<QuicChannel> sendMaxDataSize(long newMaxDataSize) {
        if (newMaxDataSize < this.maxDataSize) {
            throw new IllegalArgumentException("maxDataSize can only increase: current=" + this.maxDataSize + ", requested=" + newMaxDataSize);
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAM_DATA);
            byte[] sidBytes = QuicVarInt.encode(streamId);
            byte[] valBytes = QuicVarInt.encode(newMaxDataSize);
            int totalSize = typeBytes.length + sidBytes.length + valBytes.length;
            byte[] frame = new byte[totalSize];
            int pos = 0;
            System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
            pos += typeBytes.length;
            System.arraycopy(sidBytes, 0, frame, pos, sidBytes.length);
            pos += sidBytes.length;
            System.arraycopy(valBytes, 0, frame, pos, valBytes.length);
            this.parent.asyncChannel().sendDataFrame(ByteBuf.wrap(frame), future);
            future.onCompleted(f -> this.maxDataSize = Math.max(this.maxDataSize, newMaxDataSize));
        } catch (Throwable e) {
            future.failed(e);
        }
        return future;
    }
}
