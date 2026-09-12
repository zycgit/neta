/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.ssl.SslContext;
/**
 * QUIC stream-level channel with an independent pipeline, created and managed by its parent {@link QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel
 */
public class QuicStreamChannel extends NetChannel implements SoSubChannel {
    private final long        streamId;
    private final QuicChannel parent;
    private volatile long     maxDataSize;
    /** Timestamp of the most recent send or receive activity on this stream, in milliseconds, used for stream-level idle timeout checks. */
    private volatile long     lastActivityTime;

    /**
     * Constructs a stream-level channel from an already created async stream channel.
     * <p>Only invoked internally by {@link QuicChannel}.
     */
    QuicStreamChannel(long channelId, long streamId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer,//
            QuicStreamChannelAsync asyncChannel, SoContextService soContext, QuicChannel parent, long initMaxDataSize) {
        super(channelId, monitor, forListen, initializer, asyncChannel, soContext);
        this.streamId = streamId;
        this.parent = parent;
        this.maxDataSize = initMaxDataSize;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Returns the QUIC stream ID bound to this channel.
     */
    public long getStreamId() {
        return this.streamId;
    }

    /**
     * Returns the effective data limit for the current stream.
     * <p>This value only increases through {@link #sendMaxDataSize(long)} or MAX_STREAM_DATA received from the peer.
     */
    public long getMaxDataSize() {
        return this.maxDataSize;
    }

    /**
     * Updates the stream data limit when the peer sends a MAX_STREAM_DATA frame.
     * <p>Smaller values are ignored silently.
     */
    void updateMaxDataSize(long newMaxDataSize) {
        this.maxDataSize = Math.max(this.maxDataSize, newMaxDataSize);
    }

    /**
     * Returns the most recent activity timestamp.
     */
    long getLastActivityTime() {
        return this.lastActivityTime;
    }

    /**
     * Updates the most recent activity timestamp.
     */
    void touchActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Returns the parent connection channel that owns this stream.
     */
    @Override
    public QuicChannel getParent() {
        return this.parent;
    }

    /**
     * Returns the SSL context from the parent QUIC connection.
     * @return returns null when SSL is not enabled
     */
    public SslContext getSslContext() {
        return this.parent.getSslContext();
    }

    /**
     * Returns whether this stream is bidirectional.
     */
    public boolean isBidi() {
        return (this.streamId & 0x02) == 0;
    }

    /**
     * Returns whether this stream is unidirectional.
     */
    public boolean isUni() {
        return (this.streamId & 0x02) != 0;
    }

    /**
     * Sends a RESET_STREAM frame to terminate the current stream immediately with the given error code and final size.
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
     * Sends a STOP_SENDING frame to request that the peer stop sending more data on this stream.
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
     * Sends raw bytes directly to this QUIC stream as a STREAM frame.
     * <p>This method bypasses the upper-layer protocol pipeline.
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
     * Sends a MAX_STREAM_DATA frame to raise the receive-side flow-control limit for this stream.
     * <p>The new value must be greater than or equal to the current value.
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
