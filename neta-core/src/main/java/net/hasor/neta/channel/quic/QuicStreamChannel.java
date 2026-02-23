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
import java.io.IOException;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

/**
 * Stream-level QUIC channel.
 * <p>
 * Each QUIC stream is represented by its own {@code QuicStreamChannel}, which has
 * a fixed {@link #getStreamId() stream ID} and its own protocol pipeline.
 * This channel is created and managed by the parent {@link QuicChannel} via
 * {@link QuicChannel#newStream(long)} or {@link QuicChannel#newStream(long, ProtoInitializer)}.
 * <p>
 * <b>Receive path</b>: {@link QuicChannel} delivers data for a specific stream
 * to the corresponding {@code QuicStreamChannel}, which processes it through
 * its own pipeline. The stream-level FIN flag is exposed via
 * {@link #isRcvFinReceived()}.
 * <p>
 * <b>Send path</b>: Data written through the pipeline is sent via
 * {@link QuicAsyncStreamChannel} as QUIC STREAM frames without FIN.
 * When the stream is complete, calling {@link #close()} sends a
 * zero-length STREAM frame with FIN to signal end-of-stream.
 * <p>
 * Implements {@link SoSubChannel}: the parent is the connection-level {@link QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel
 */
public class QuicStreamChannel extends NetChannel implements SoSubChannel {
    /** Stream ID used for DATAGRAM channels. Datagram channels are not bound to a real QUIC stream. */
    public static final long DATAGRAM_STREAM_ID = -1L;

    private final    long        streamId;
    private final    QuicChannel parentChannel;
    private volatile boolean     rcvFinReceived;

    QuicStreamChannel(long channelId, long streamId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService soContext, QuicChannel parentChannel) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, soContext);
        this.streamId = streamId;
        this.parentChannel = parentChannel;
    }

    /** Returns the QUIC stream ID bound to this channel. */
    public long getStreamId() {
        return this.streamId;
    }

    /** Returns the parent connection-level {@link QuicChannel}. */
    public QuicChannel getParentChannel() {
        return this.parentChannel;
    }

    @Override
    public SoChannel<?> getParent() {
        return this.parentChannel;
    }

    /**
     * Returns {@code true} if this channel represents a QUIC DATAGRAM (RFC 9221)
     * rather than a regular QUIC stream.
     */
    public boolean isDatagram() {
        return this.streamId == DATAGRAM_STREAM_ID;
    }

    // ── RCV FIN ────────────────────────────────────────────────────────

    /** Returns {@code true} if the remote peer has sent FIN on this stream. */
    public boolean isRcvFinReceived() {
        return this.rcvFinReceived;
    }

    /** Marks this stream as having received FIN from the remote peer. */
    void setRcvFinReceived() {
        this.rcvFinReceived = true;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    public Future<NetChannel> close() {
        this.parentChannel.removeStream(this.streamId);
        return super.close();
    }

    @Override
    public void closeNow() {
        this.parentChannel.removeStream(this.streamId);
        super.closeNow();
    }
}
