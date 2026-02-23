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
import net.hasor.neta.channel.*;

/**
 * Connection-level {@link AsyncChannel} for QUIC.
 * <p>
 * Handles the write path for the connection-level {@link QuicChannel}.
 * In the two-layer model, most application data flows through per-stream
 * {@link QuicAsyncStreamChannel} instances. This connection-level channel
 * sends data on stream 0 (default) without FIN.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncConnectionChannel implements AsyncChannel {
    private final    long             channelId;
    private final    SoConfig         soConfig;
    private final    SocketAddress    localAddress;
    private final    SocketAddress    remoteAddress;
    private final    AtomicBoolean    closed  = new AtomicBoolean(false);
    private final    AtomicBoolean    writing = new AtomicBoolean(false);
    private final    SoContextService context;
    private volatile QuicChannel      quicChannel;

    QuicAsyncConnectionChannel(long channelId, SoConfig soConfig, SocketAddress localAddress, SocketAddress remoteAddress, SoContextService context) {
        this.channelId = channelId;
        this.soConfig = soConfig;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.context = context;
    }

    /** Set the back-reference to the owning QuicChannel (called after construction). */
    void setQuicChannel(QuicChannel quicChannel) {
        this.quicChannel = quicChannel;
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
        return !this.closed.get() && this.quicChannel != null && this.quicChannel.isConnectionOpen();
    }

    @Override
    public void close() throws IOException {
        this.closed.compareAndSet(false, true);
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (!isOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("QUIC connection is closed.");
            this.context.notifySndChannelException(this.channelId, true, err);
            wContext.purge(err);
            return;
        }
        if (wContext.isEmpty()) {
            return;
        }
        if (this.writing.compareAndSet(false, true)) {
            QuicChannel qc = (QuicChannel) channel;
            // Connection-level stream ID is 0
            QuicWriteTask task = new QuicWriteTask(channel, 0L, qc, wContext, this.context);
            this.context.submitSoTask(task, this).onFinal(f -> {
                this.writing.set(false);
            });
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Use QuicAsyncClientChannel for client connections.");
    }
}
