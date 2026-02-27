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
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Stream-level QUIC channel.
 * <p>
 * Each QUIC stream is represented by its own {@code QuicStreamChannel}, which has
 * a fixed {@link #getStreamId() stream ID} and its own protocol pipeline.
 * This channel is created and managed by the parent {@link QuicChannel} via
 * {@link QuicChannel#newBidiStream()} or {@link QuicChannel#newUniStream()}.
 * <p>
 * <b>Receive path</b>: {@link QuicChannel} delivers data for a specific stream
 * to the corresponding {@code QuicStreamChannel}, which processes it through
 * its own pipeline.
 * <p>
 * <b>Send path</b>: Data written through the pipeline is packaged by
 * {@link QuicStreamChannelAsync} as QUIC STREAM frames and forwarded to the
 * parent connection channel for transmission.
 * <p>
 * Implements {@link SoSubChannel}: the parent is the connection-level {@link QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel
 */
public class QuicStreamChannel extends NetChannel implements SoSubChannel {
    private final    long        streamId;
    private final    QuicChannel parent;
    private volatile long        maxDataSize;
    /** Last time (in milliseconds) data was sent or received on this stream. Used for stream-level idle timeout. */
    private volatile long        lastActivityTime;

    /**
     * Creates a stream-level channel from an already-constructed async stream channel.
     * Called exclusively by {@link QuicChannel} after the QUIC handshake completes.
     * <p>
     * Both the peer's announced limit and the effective limit are passed in from the
     * parent {@link QuicChannel}. The effective limit ({@link #getMaxDataSize()}) is
     * already computed as {@code Math.min(localMax, peerMax)} by the caller.
     * @param channelId unique channel ID allocated by the context
     * @param streamId QUIC stream ID (per RFC 9000 §2.1)
     * @param monitor I/O traffic monitor
     * @param forListen the server-side listen handle, or {@code null} for client-initiated streams
     * @param initializer pipeline initializer for this stream
     * @param asyncChannel the low-level stream async channel
     * @param soContext the Neta context service
     * @param parent the parent connection-level {@link QuicChannel}
     * @param initMaxDataSize the peer's announced {@code initial_max_stream_data} (immutable)
     */
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
     * Returns the effective per-stream data limit currently in use.
     * Initially {@code min(localMax, peerMax)} as computed during stream creation,
     * and can only <b>increase</b> via {@link #sendMaxDataSize(long)} or peer's MAX_STREAM_DATA.
     * @return current effective stream data limit
     */
    public long getMaxDataSize() {
        return this.maxDataSize;
    }

    /**
     * Updates the per-stream flow-control limit when the peer sends a MAX_STREAM_DATA frame.
     * The limit can only <b>increase</b>; smaller values are silently ignored.
     * <p>Package-private — called by {@link QuicChannelAsync#dispatchReceivedFrames(byte[])}.
     * @param newMaxDataSize the new maximum announced by the peer
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

    /**
     * Returns the parent connection-level {@link QuicChannel} that owns this stream.
     * @return the parent {@link QuicChannel}
     */
    @Override
    public QuicChannel getParent() {
        return this.parent;
    }

    /**
     * Returns the {@link SslContext} from the parent QUIC connection.
     * @return the connection-level SSL context, or {@code null} if SSL is disabled
     */
    public SslContext getSslContext() {
        return this.parent.getSslContext();
    }

    /**
     * Returns {@code true} if this is a <b>bidirectional</b> stream.
     * <p>Per RFC 9000 §2.1, bit 1 (value {@code 0x02}) of the stream ID encodes the
     * stream type: {@code 0} = bidirectional, {@code 1} = unidirectional.
     * Stream IDs 0, 1, 4, 5, 8, 9, … are bidirectional.
     * @return {@code true} if this stream is bidirectional
     */
    public boolean isBidi() {
        return (this.streamId & 0x02) == 0;
    }

    /**
     * Returns {@code true} if this is a <b>unidirectional</b> stream.
     * <p>Per RFC 9000 §2.1, bit 1 (value {@code 0x02}) of the stream ID encodes the
     * stream type: {@code 0} = bidirectional, {@code 1} = unidirectional.
     * Stream IDs 2, 3, 6, 7, 10, 11, … are unidirectional.
     * @return {@code true} if this stream is unidirectional
     */
    public boolean isUni() {
        return (this.streamId & 0x02) != 0;
    }

    /**
     * Sends a RESET_STREAM frame (RFC 9000 §19.4) to abruptly terminate this stream.
     * The peer will discard any buffered data and stop delivering data on this stream.
     * @param errorCode application-defined error code indicating why the stream is being reset
     * @param finalSize the total number of bytes that were sent on this stream before the reset
     * @return a {@link Future} that completes with the parent {@link QuicChannel} once the
     * frame has been handed off for transmission, or fails if the connection is closed
     */
    public Future<QuicChannel> sendReset(long errorCode, long finalSize) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.RESET_STREAM);
            byte[] sidBytes = QuicVarInt.encode(this.streamId);
            byte[] errBytes = QuicVarInt.encode(errorCode);
            byte[] sizeBytes = QuicVarInt.encode(finalSize);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + sidBytes.length + errBytes.length + sizeBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(sidBytes);
            frame.writeBytes(errBytes);
            frame.writeBytes(sizeBytes);
            this.parent.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    /**
     * Sends a STOP_SENDING frame (RFC 9000 §19.5) to ask the peer to cease sending
     * data on this stream.  The peer is expected to respond with a RESET_STREAM frame.
     * @param errorCode application-defined error code indicating why the stream is being stopped
     * @return a {@link Future} that completes with the parent {@link QuicChannel} once the
     * frame has been handed off for transmission, or fails if the connection is closed
     */
    public Future<QuicChannel> sendStop(long errorCode) {
        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.STOP_SENDING);
            byte[] sidBytes = QuicVarInt.encode(this.streamId);
            byte[] errBytes = QuicVarInt.encode(errorCode);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + sidBytes.length + errBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(sidBytes);
            frame.writeBytes(errBytes);
            this.parent.asyncChannel().sendDataFrame(frame, future);
        } catch (Throwable e) {
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }

    /**
     * Sends a MAX_STREAM_DATA frame (RFC 9000 §19.10) to increase the receive-side
     * flow-control limit for this stream, allowing the peer to send more data.
     * The new limit must be <b>greater than or equal to</b> the current
     * {@link #getMaxDataSize()} — shrinking is not allowed.
     * <p>
     * After the frame has been successfully transmitted over UDP, the local
     * {@code useMaxDataSize} is updated to {@code newMaxDataSize}.
     * @param newMaxDataSize the new cumulative maximum number of bytes the peer may send on this stream
     * (must be &ge; current {@code useMaxDataSize})
     * @return a {@link Future} that completes with the parent {@link QuicChannel} once the
     * frame has been handed off for transmission, or fails if the connection is closed
     * @throws IllegalArgumentException if {@code newMaxDataSize} is smaller than the current limit
     */
    public Future<QuicChannel> sendMaxDataSize(long newMaxDataSize) {
        if (newMaxDataSize < this.maxDataSize) {
            throw new IllegalArgumentException("maxDataSize can only increase: current=" + this.maxDataSize + ", requested=" + newMaxDataSize);
        }

        BasicFuture<QuicChannel> future = new BasicFuture<>();
        ByteBuf frame = null;
        try {
            byte[] typeBytes = QuicVarInt.encode(QuicFrameType.MAX_STREAM_DATA);
            byte[] sidBytes = QuicVarInt.encode(streamId);
            byte[] valBytes = QuicVarInt.encode(newMaxDataSize);
            frame = ByteBufAllocator.DEFAULT.buffer(typeBytes.length + sidBytes.length + valBytes.length);
            frame.writeBytes(typeBytes);
            frame.writeBytes(sidBytes);
            frame.writeBytes(valBytes);
            this.parent.asyncChannel().sendDataFrame(frame, future);
            future.onCompleted(f -> this.maxDataSize = Math.max(this.maxDataSize, newMaxDataSize));
        } catch (Throwable e) {
            IOUtils.closeQuietly(frame);
            future.failed(e);
        }
        return future;
    }
}
