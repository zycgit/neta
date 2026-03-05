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
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.AbstractUdpWriteTask;

/**
 * Stream-level {@link AsyncChannel} that routes writes to the parent {@link QuicChannel} as STREAM frames for the correct stream ID.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicStreamChannelAsync implements AsyncChannel {
    private static final Logger           logger     = Logger.getLogger(QuicStreamChannelAsync.class);
    private final        long             channelId;
    private final        long             streamId;
    private final        QuicChannel      quicChannel;
    private final        SoContextService context;
    //
    private final        AtomicBoolean    closed     = new AtomicBoolean(false);
    private final        AtomicBoolean    writing    = new AtomicBoolean(false);
    private final        AtomicLong       sendOffset = new AtomicLong(0);

    QuicStreamChannelAsync(long channelId, long streamId, QuicChannel quicChannel, SoContextService context) {
        this.channelId = channelId;
        this.streamId = streamId;
        this.quicChannel = quicChannel;
        this.context = context;
    }

    /** Builds a QUIC STREAM frame (RFC 9000 §19.8) as a ready-to-send {@link ByteBuffer}. */
    private static byte[] buildStreamData(long streamId, long offset, byte[] data, boolean fin) {
        int type = QuicFrameType.STREAM_BASE | QuicFrameType.STREAM_LEN_BIT;
        if (fin) {
            type |= QuicFrameType.STREAM_FIN_BIT;
        }
        if (offset > 0) {
            type |= QuicFrameType.STREAM_OFF_BIT;
        }

        byte[] typeBytes = QuicVarInt.encode(type);
        byte[] streamIdBytes = QuicVarInt.encode(streamId);
        byte[] offsetBytes = (offset > 0) ? QuicVarInt.encode(offset) : new byte[0];
        byte[] lengthBytes = QuicVarInt.encode(data.length);

        int totalLen = typeBytes.length + streamIdBytes.length + offsetBytes.length + lengthBytes.length + data.length;
        byte[] frame = new byte[totalLen];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(streamIdBytes, 0, frame, pos, streamIdBytes.length);
        pos += streamIdBytes.length;
        if (offset > 0) {
            System.arraycopy(offsetBytes, 0, frame, pos, offsetBytes.length);
            pos += offsetBytes.length;
        }
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
        if (this.closed.compareAndSet(false, true)) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("[QUIC-SND] stream=" + this.streamId + " close (FIN)");
            }
            try {
                long offset = this.sendOffset.get();
                byte[] finFrame = buildStreamData(this.streamId, offset, new byte[0], true);
                this.quicChannel.asyncChannel().sendDataFrame(ByteBuf.wrap(finFrame), null);
            } catch (Exception e) {
                logger.error("Failed to send FIN on QUIC stream " + this.streamId + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException("Stream channels do not support connectTo.");
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (this.closed.get()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("QUIC stream " + this.streamId + " is closed.");
            this.context.notifySndChannelException(this.channelId, true, err);
            wContext.purge(err);
            return;
        }
        if (wContext.isEmpty()) {
            return;
        }

        // Touch stream activity timestamp for idle timeout tracking
        QuicStreamChannel streamChannel = this.quicChannel.findStream(this.streamId);
        if (streamChannel != null) {
            streamChannel.touchActivity();
        }

        if (this.writing.compareAndSet(false, true)) {
            this.asyncWrite(channel, wContext);
        }
    }

    protected void asyncWrite(NetChannel channel, SoSndContext wContext) {
        QuicStreamUdpWriteTask task = new QuicStreamUdpWriteTask(channel, wContext, this.context, this.streamId, this.sendOffset);
        this.context.submitSoTask(task, this).onFinal(f -> {
            // Advance offset for the last chunk sent by this task batch.
            task.flushOffset();
            this.writing.set(false);
        });
    }

    private class QuicStreamUdpWriteTask extends AbstractUdpWriteTask {
        private final long       streamId;
        /** Shared reference to the stream-level cumulative send offset. */
        private final AtomicLong sendOffsetRef;
        /** Snapshot of the last {@code sendData} array passed to {@link #wrapSendData}. */
        private       byte[]     prevSendData;

        public QuicStreamUdpWriteTask(NetChannel netChannel, SoSndContext wContext, SoContextService context,//
                long streamId, AtomicLong sendOffsetRef) {
            super(netChannel, wContext, context);
            this.streamId = streamId;
            this.sendOffsetRef = sendOffsetRef;
        }

        @Override
        protected boolean isChannelOpen() {
            return isOpen();
        }

        @Override
        protected int doSend(byte[] data) {
            ByteBuf byteBuf = ByteBuf.wrap(data);
            int sent = quicChannel.asyncChannel().sendDataFrame(byteBuf, null);
            if (context.getConfig().isPrintLog()) {
                logger.info("[QUIC-SND] stream=" + this.streamId + " bytes=" + data.length + " sent=" + sent);
            }
            return sent;
        }

        /** Converts raw application bytes into a QUIC STREAM frame. */
        @Override
        protected byte[] wrapSendData(byte[] sendData) {
            // Detect data rotation: prevSendData was fully transmitted, advance offset.
            if (this.prevSendData != null && this.prevSendData != sendData) {
                this.sendOffsetRef.addAndGet(this.prevSendData.length);
            }
            this.prevSendData = sendData;
            long offset = this.sendOffsetRef.get();
            return buildStreamData(this.streamId, offset, sendData, false);
        }

        /** Advances the shared offset for the final chunk that was sent by this task batch. */
        void flushOffset() {
            if (this.prevSendData != null) {
                this.sendOffsetRef.addAndGet(this.prevSendData.length);
                this.prevSendData = null;
            }
        }
    }
}