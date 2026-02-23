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
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * Stream-level {@link AsyncChannel} for QUIC.
 * <p>
 * Each {@link QuicStreamChannel} has its own {@code QuicAsyncStreamChannel} that
 * routes write operations to the underlying {@link QuicChannel} with the correct
 * stream ID. Closing this channel sends a STREAM frame with FIN on the stream
 * without affecting the QUIC connection.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncStreamChannel implements AsyncChannel {
    private static final Logger           logger  = Logger.getLogger(QuicAsyncStreamChannel.class);
    private final        long             channelId;
    private final        long             streamId;
    private final        QuicChannel      quicChannel;
    private final        SoConfig         soConfig;
    private final        SocketAddress    localAddress;
    private final        SocketAddress    remoteAddress;
    private final        AtomicBoolean    closed  = new AtomicBoolean(false);
    private final        AtomicBoolean    writing = new AtomicBoolean(false);
    private final        SoContextService context;

    QuicAsyncStreamChannel(long channelId, long streamId, QuicChannel quicChannel, SoConfig soConfig, SocketAddress localAddress, SocketAddress remoteAddress, SoContextService context) {
        this.channelId = channelId;
        this.streamId = streamId;
        this.quicChannel = quicChannel;
        this.soConfig = soConfig;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.context = context;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && this.quicChannel.isConnectionOpen();
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            // Send a zero-length STREAM frame with FIN to signal end-of-stream
            try {
                this.quicChannel.sendStreamData(this.streamId, new byte[0], true);
            } catch (Exception e) {
                logger.error("Failed to send FIN on QUIC stream " + this.streamId + ": " + e.getMessage());
            }
            try {
                this.quicChannel.removeStream(this.streamId);
            } catch (Exception e) {
                logger.error("Failed to close QUIC stream " + this.streamId + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (!isOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("QUIC stream " + this.streamId + " is closed.");
            this.context.notifySndChannelException(this.channelId, true, err);
            wContext.purge(err);
            return;
        }
        if (wContext.isEmpty()) {
            return;
        }
        if (this.writing.compareAndSet(false, true)) {
            QuicWriteTask task = new QuicWriteTask(channel, this.streamId, this.quicChannel, wContext, this.context);
            this.context.submitSoTask(task, this).onFinal(f -> {
                this.writing.set(false);
            });
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Stream channels do not support connectTo.");
    }
}
