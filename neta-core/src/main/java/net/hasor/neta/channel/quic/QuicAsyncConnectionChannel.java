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
 * Connection-level {@link AsyncChannel} for QUIC.
 * <p>
 * Handles the write path for a QUIC connection. When the pipeline's
 * outbound data reaches this channel, it is sent as QUIC STREAM frames
 * on the default stream (stream 0).
 * <p>
 * For stream-specific sends, use {@link QuicConnection#sendStreamData(long, byte[], boolean)} directly.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncConnectionChannel implements AsyncChannel {
    private static final Logger         logger = Logger.getLogger(QuicAsyncConnectionChannel.class);
    private final        long           channelId;
    private final        QuicConnection quicConn;
    private final        SoConfig       soConfig;
    private final        SocketAddress  localAddress;
    private final        SocketAddress  remoteAddress;
    private final        AtomicBoolean  closed = new AtomicBoolean(false);

    QuicAsyncConnectionChannel(long channelId, QuicConnection quicConn, SoConfig soConfig, SocketAddress localAddress, SocketAddress remoteAddress) {
        this.channelId = channelId;
        this.quicConn = quicConn;
        this.soConfig = soConfig;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
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
        return !this.closed.get() && this.quicConn.isOpen();
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            this.quicConn.close();
        }
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (!isOpen()) {
            wContext.purge(new IOException("QUIC connection is closed"));
            return;
        }
        SoSndData data;
        while ((data = wContext.popData()) != null) {
            try {
                byte[] payload;
                while ((payload = data.transferPull()) != null) {
                    if (payload.length > 0) {
                        // Default write target is stream 0
                        this.quicConn.sendStreamData(0, payload, false);
                    }
                }
                data.completed();
            } catch (Exception e) {
                data.failed(e);
                logger.error("QUIC connection write error: " + e.getMessage());
            }
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Use QuicAsyncClientChannel for client connections.");
    }
}
