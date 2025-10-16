/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.sctp;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * SCTP-specific implementation of the asynchronous channel.
 * Handles read/write operations and connection management for SCTP associations.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SctpAsyncChannel implements AsyncChannel {
    private static final Logger           logger = Logger.getLogger(SctpAsyncChannel.class);
    protected final      long             channelId;
    protected final      SctpChannel      channel;
    protected final      SocketAddress    localAddress;
    protected final      SocketAddress    remoteAddress;
    protected final      SoContextService context;
    protected final      SctpSoConfig     soConfig;
    //
    private final        AtomicBoolean    writing;
    // Client Mode
    private final        Selector         selector;
    private final        ByteBufAllocator bufAllocator;
    private final        ByteBuffer       receiveBuffer;

    SctpAsyncChannel(long channelId, SctpChannel channel, SocketAddress localAddr, SocketAddress remoteAddr, SoContextService context, SctpSoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = localAddr;
        this.remoteAddress = remoteAddr;
        this.context = context;
        this.soConfig = soConfig;
        this.writing = new AtomicBoolean(false);

        if (this.localAddress == null) {
            this.selector = Selector.open();
            this.bufAllocator = this.context.getByteBufAllocator();
            this.receiveBuffer = this.bufAllocator.jvmBuffer(SctpSoConfigUtils.getRcvPacketSize(this.soConfig));
        } else {
            this.selector = null;
            this.bufAllocator = null;
            this.receiveBuffer = null;
        }
    }

    @Override
    public SctpSoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    //

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
        if (this.selector != null) {
            IOUtils.closeQuietly(this.selector);
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        if (this.selector == null) {
            throw new IllegalStateException("Not a client channel");
        }
        try {
            this.channel.configureBlocking(false);
            if (this.channel.connect(this.remoteAddress)) {
                this.completeConnect(initializer, future, true);
            } else {
                this.channel.register(this.selector, SelectionKey.OP_CONNECT, new ConnectContext(initializer, future));
                this.startReceiveLoop();
            }
        } catch (Throwable e) {
            future.failed(e);
        }
    }

    private void completeConnect(ProtoInitializer initializer, Future<NetChannel> future, boolean startLoop) throws Throwable {
        net.hasor.neta.channel.sctp.SctpChannel netChannel =//
                new net.hasor.neta.channel.sctp.SctpChannel(this.channelId, new NetMonitor(), null, initializer, this, this.context);
        this.context.initChannel(netChannel, false);
        future.completed(netChannel);

        this.channel.register(this.selector, SelectionKey.OP_READ, netChannel);
        if (startLoop) {
            this.startReceiveLoop();
        }
    }

    private void startReceiveLoop() {
        this.context.submitSoTask(new SoDelayTask(0), this).onFinal(f -> {
            try {
                receiveLoop();
            } catch (Throwable e) {
                // Should be handled in receiveLoop, but just in case
                SoRcvException ee = e instanceof SoRcvException ? (SoRcvException) e : new SoRcvException(e.getMessage(), e);
                this.context.notifyRcvChannelException(this.channelId, false, ee);
            }
        });
    }

    private void receiveLoop() {
        if (!isOpen()) {
            return;
        }
        try {
            if (this.selector.select(100) > 0) {
                Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (key.isConnectable()) {
                            this.channel.finishConnect();
                            ConnectContext ctx = (ConnectContext) key.attachment();
                            finishConnect(ctx.initializer, ctx.future);
                        }
                        if (key.isReadable()) {
                            net.hasor.neta.channel.sctp.SctpChannel sctpChannel = (net.hasor.neta.channel.sctp.SctpChannel) key.attachment();
                            readSocket(sctpChannel);
                        }
                    } catch (Throwable e) {
                        if (key.attachment() instanceof ConnectContext) {
                            ((ConnectContext) key.attachment()).future.failed(e);
                        } else {
                            SoRcvException ee;
                            if (e instanceof SoRcvException) {
                                ee = (SoRcvException) e;
                            } else {
                                ee = new SoRcvException(e.getMessage(), e);
                            }
                            this.context.notifyRcvChannelException(this.channelId, false, ee);
                        }
                    }
                }
            }
            startReceiveLoop();
        } catch (Throwable e) {
            SoRcvException ee;
            if (e instanceof SoRcvException) {
                ee = (SoRcvException) e;
            } else {
                ee = new SoRcvException(e.getMessage(), e);
            }
            this.context.notifyRcvChannelException(this.channelId, false, ee);
        }
    }

    private void finishConnect(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
        this.completeConnect(initializer, future, false);
    }

    private void readSocket(net.hasor.neta.channel.sctp.SctpChannel sctpChannel) throws IOException {
        this.receiveBuffer.clear();
        MessageInfo info = this.channel.receive(this.receiveBuffer, this.context, sctpChannel.notificationHandler());
        if (info == null) {
            return;
        }

        this.fireRcvData(sctpChannel, info, this.receiveBuffer);
        if (!info.isComplete()) {
            while (true) {
                this.receiveBuffer.clear();
                info = this.channel.receive(this.receiveBuffer, this.context, sctpChannel.notificationHandler());
                if (info == null) {
                    break;
                }
                this.fireRcvData(sctpChannel, info, this.receiveBuffer);
                if (info.isComplete()) {
                    break;
                }
            }
        }
    }

    private void fireRcvData(net.hasor.neta.channel.sctp.SctpChannel socket, MessageInfo info, ByteBuffer receiveBuffer) {
        ByteBuf byteBuf = this.bufAllocator.buffer(receiveBuffer.position());
        receiveBuffer.flip();
        byteBuf.writeBuffer(receiveBuffer);
        byteBuf.markWriter();
        int readableBytes = byteBuf.readableBytes();
        socket.getNetMonitor().updateRcvCounter(readableBytes);
        if (logger.isDebugEnabled()) {
            logger.debug("rcv(" + this.channelId + ") the receive " + readableBytes + " bytes");
        }

        final SctpMessage message = SctpMessage.of(info, byteBuf);
        this.context.notifyRcvChannelData(socket.getChannelId(), message);
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (wContext.isEmpty()) {
            return;
        }

        if (this.writing.compareAndSet(false, true)) {
            this.asyncWrite(channel, wContext);
        }
    }

    private void asyncWrite(NetChannel channel, SoSndContext wContext) {
        SctpWriteTask task = new SctpWriteTask(channel, this.channel, wContext, this.context);
        this.context.submitSoTask(task, this).onFinal(f -> {
            this.writing.set(false);
        });
    }

    private static class ConnectContext {
        final ProtoInitializer   initializer;
        final Future<NetChannel> future;

        ConnectContext(ProtoInitializer initializer, Future<NetChannel> future) {
            this.initializer = initializer;
            this.future = future;
        }
    }
}