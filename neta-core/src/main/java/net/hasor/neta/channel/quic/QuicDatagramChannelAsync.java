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
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.AbstractUdpWriteTask;

/**
 * DATAGRAM-level {@link AsyncChannel} that routes writes as unreliable DATAGRAM frames (RFC 9221) through the parent {@link QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicDatagramChannelAsync implements AsyncChannel {
    private static final Logger           logger  = Logger.getLogger(QuicDatagramChannelAsync.class);
    private final        long             channelId;
    private final        QuicChannel      quicChannel;
    private final        SoContextService context;
    //
    private final        AtomicBoolean    closed  = new AtomicBoolean(false);
    private final        AtomicBoolean    writing = new AtomicBoolean(false);

    QuicDatagramChannelAsync(long channelId, QuicChannel quicChannel, SoContextService context) {
        this.channelId = channelId;
        this.quicChannel = quicChannel;
        this.context = context;
    }

    /** Builds a QUIC DATAGRAM frame (RFC 9221 §4) as a ready-to-send {@link ByteBuffer}. */
    private static byte[] buildDatagramData(byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.DATAGRAM_LEN);
        byte[] lengthBytes = QuicVarInt.encode(data.length);

        int totalLen = typeBytes.length + lengthBytes.length + data.length;
        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(lengthBytes, 0, frame, pos, lengthBytes.length);
        pos += lengthBytes.length;
        if (data.length > 0) {
            System.arraycopy(data, 0, frame, pos, data.length);
        }
        return frame;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SoConfig getSoConfig() {
        return this.quicChannel.getConfig();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.quicChannel.getLocalAddr();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.quicChannel.getRemoteAddr();
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && !this.quicChannel.isClose();
    }

    @Override
    public void close() throws IOException {
        this.closed.compareAndSet(false, true);
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Datagram channels do not support connectTo.");
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (this.closed.get()) {
            return;
        }
        if (wContext.isEmpty()) {
            return;
        }

        if (this.writing.compareAndSet(false, true)) {
            this.asyncWrite(channel, wContext);
        }
    }

    protected void asyncWrite(NetChannel channel, SoSndContext wContext) {
        QuicDatagramUdpWriteTask task = new QuicDatagramUdpWriteTask(channel, wContext, this.context);
        this.context.submitSoTask(task, this).onFinal(f -> {
            this.writing.set(false);
        });
    }

    private class QuicDatagramUdpWriteTask extends AbstractUdpWriteTask {
        public QuicDatagramUdpWriteTask(NetChannel netChannel, SoSndContext wContext, SoContextService context) {
            super(netChannel, wContext, context);
        }

        @Override
        protected boolean isChannelOpen() {
            return isOpen();
        }

        @Override
        protected int doSend(byte[] data) throws IOException {
            ByteBuf byteBuf = ByteBuf.wrap(data);
            return quicChannel.asyncChannel().sendDataFrame(byteBuf, null);
        }

        @Override
        protected byte[] wrapSendData(byte[] sendData) {
            return buildDatagramData(sendData);
        }
    }
}
