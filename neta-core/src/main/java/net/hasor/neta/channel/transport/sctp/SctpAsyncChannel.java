/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;
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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
/**
 * Asynchronous channel adapter built on top of JDK {@link com.sun.nio.sctp.SctpChannel}.
 * <p>This class has two operating modes:
 * <ul>
 *   <li><b>Client mode</b>: created by {@link SctpProvider} with {@code localAddress == null}.
 *       In this mode the instance owns a dedicated {@link Selector} and drives its own
 *       connect and receive loop.</li>
 *   <li><b>Accepted server mode</b>: created by {@link SctpAsyncServerChannel} to wrap an
 *       accepted SCTP socket. Inbound reads are driven by the server-side selector loop.</li>
 * </ul>
 * <p><b>Execution model:</b>
 * <pre>
 *   Client mode:
 *     connectTo()
 *        --> selector(OP_CONNECT / OP_READ)
 *        --> receive MessageInfo and payload
 *        --> wrap as SctpMessage
 *        --> notifyRcvChannelData(...)
 *   Outbound path (shared by both modes):
 *     SoSndContext --> SctpWriteTask --> SctpChannel.send(...)
 * </pre>
 * <p>The implementation preserves SCTP message semantics: every receive operation carries
 * {@link MessageInfo} metadata together with the payload, and all sends are performed through
 * {@link SctpWriteTask} instead of exposing a stream-style API.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SctpAsyncServerChannel
 * @see SctpWriteTask
 * @see SctpSoConfig
 */
class SctpAsyncChannel implements AsyncChannel {
    private static final Logger      logger = Logger.getLogger(SctpAsyncChannel.class);
    protected final long             channelId;
    protected final SctpChannel      channel;
    protected final SocketAddress    localAddress;
    protected final SocketAddress    remoteAddress;
    protected final SoContextService context;
    protected final SctpSoConfig     soConfig;
    //
    private final AtomicBoolean writing;
    // Client Mode
    private final Selector         selector;
    private final ByteBufAllocator bufAllocator;
    private final ByteBuffer       receiveBuffer;

    SctpAsyncChannel(long channelId, SctpChannel channel, SocketAddress localAddr, SocketAddress remoteAddr, SoContextService context, SctpSoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = localAddr;
        this.remoteAddress = remoteAddr;
        this.context = context;
        this.soConfig = soConfig;
        this.writing = new AtomicBoolean(false);

        SctpSoConfigUtils.configSocket(this.soConfig, this.channel);

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

    /**
     * Return the SCTP configuration used by the current channel.
     * @return the SCTP configuration object
     */
    @Override
    public SctpSoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Return the internal channel identifier assigned by the framework.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the local bound address.
     * <p>In client mode this value is usually null until the underlying connection has been
     * established and the actual local address is managed by the JDK.
     * @return the local address
     */
    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    /**
     * Return the remote address.
     * @return the remote address
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    /**
     * Determine whether the underlying SCTP channel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Close the underlying channel together with its selector and receive-buffer resources.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        this.channel.close();
        if (this.selector != null) {
            IOUtils.closeQuietly(this.selector);
        }
        if (this.receiveBuffer != null && ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(this.receiveBuffer);
        }
    }

    /**
     * Initiate a connection to the remote endpoint and create the framework-level {@link NetChannel}
     * after the connection succeeds.
     * <p>This method is only valid in client mode. It throws an exception if the current instance
     * wraps a server-accepted channel.
     * @param initializer the protocol initializer
     * @param future the future that receives the connection result
     */
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
        net.hasor.neta.channel.transport.sctp.SctpChannel netChannel =//
                new net.hasor.neta.channel.transport.sctp.SctpChannel(this.channelId, new NetMonitor(), null, initializer, this, this.context);
        this.context.initChannel(netChannel, true);
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
                            net.hasor.neta.channel.transport.sctp.SctpChannel sctpChannel = (net.hasor.neta.channel.transport.sctp.SctpChannel) key.attachment();
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
            if (isOpen()) {
                startReceiveLoop();
            }
        }
    }

    private void finishConnect(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
        this.completeConnect(initializer, future, false);
    }

    private void readSocket(net.hasor.neta.channel.transport.sctp.SctpChannel sctpChannel) throws IOException {
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

    private void fireRcvData(net.hasor.neta.channel.transport.sctp.SctpChannel socket, MessageInfo info, ByteBuffer receiveBuffer) {
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

    /**
     * Trigger an asynchronous send cycle.
     * <p>When the send context is not empty and no write task is currently running, a new
     * {@link SctpWriteTask} is submitted.
     * @param channel the associated framework channel
     * @param wContext the send context
     */
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
