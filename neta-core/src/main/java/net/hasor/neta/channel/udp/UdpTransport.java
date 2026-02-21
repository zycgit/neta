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
package net.hasor.neta.channel.udp;
import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.DefaultSoTask;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoDelayTask;

/**
 * Reusable UDP datagram transport layer. Manages a {@link DatagramChannel},
 * {@link Selector}, and a task-driven receive loop that delivers raw
 * datagrams to a {@link DatagramReceiver} callback.
 * <p>
 * Both UDP and QUIC channel implementations share this class to avoid
 * duplicating low-level DatagramChannel / Selector management code.
 * <p>
 * Usage:
 * <pre>{@code
 * UdpTransport transport = UdpTransport.open(channelId, context, 65535, owner);
 * transport.bind(address);
 * transport.startReceiveLoop(receiver, onClose, onError);
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version 2026-02-21
 */
public class UdpTransport implements Closeable {
    private static final Logger           logger = Logger.getLogger(UdpTransport.class);
    private final        long             ownerChannelId;
    private final        DatagramChannel  channel;
    private final        Selector         selector;
    private final        SoContextService context;
    private final        ByteBuffer       receiveBuffer;
    private final        Object           taskOwner;
    private final        AtomicBoolean    closed = new AtomicBoolean(false);

    /**
     * Callback interface for received datagrams.
     * The {@code data} ByteBuffer is positioned at 0 with the limit set to the
     * number of bytes received. Implementations must consume or copy the data
     * before returning, as the buffer will be reused for the next read.
     */
    public interface DatagramReceiver {
        void onDatagram(SocketAddress remoteAddr, ByteBuffer data) throws IOException;
    }

    // ── Factory Methods ────────────────────────────────────────────────

    /**
     * Opens a new DatagramChannel-based transport.
     * @param ownerChannelId channel ID of the owning server/client channel
     * @param context the SoContextService for task scheduling
     * @param receiveBufferSize size of the internal receive buffer (bytes)
     * @param taskOwner object passed to {@code submitSoTask} as the task owner
     */
    public static UdpTransport open(long ownerChannelId, SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new UdpTransport(ownerChannelId, channel, context, receiveBufferSize, taskOwner);
    }

    /**
     * Wraps an existing DatagramChannel into a transport.
     * @param ownerChannelId channel ID of the owning server/client channel
     * @param channel pre-opened DatagramChannel
     * @param context the SoContextService for task scheduling
     * @param receiveBufferSize size of the internal receive buffer (bytes)
     * @param taskOwner object passed to {@code submitSoTask} as the task owner
     */
    public static UdpTransport wrap(long ownerChannelId, DatagramChannel channel, SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        return new UdpTransport(ownerChannelId, channel, context, receiveBufferSize, taskOwner);
    }

    private UdpTransport(long ownerChannelId, DatagramChannel channel, SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        this.ownerChannelId = ownerChannelId;
        this.channel = channel;
        this.selector = Selector.open();
        this.context = context;
        this.receiveBuffer = ByteBuffer.allocateDirect(receiveBufferSize);
        this.taskOwner = taskOwner;
    }

    // ── Setup ──────────────────────────────────────────────────────────

    /**
     * Binds the underlying DatagramChannel to the given address,
     * sets it to non-blocking mode, and registers it with the Selector for reading.
     */
    public void bind(SocketAddress addr) throws IOException {
        this.channel.bind(addr);
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);
    }

    /**
     * Connects the underlying DatagramChannel to the given remote address,
     * sets it to non-blocking mode, and registers it with the Selector for reading.
     */
    public void connect(SocketAddress addr) throws IOException {
        this.channel.connect(addr);
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);
    }

    // ── Receive Loop ───────────────────────────────────────────────────

    /**
     * Starts a task-driven receive loop. Each received datagram is delivered
     * to the given {@link DatagramReceiver}. The loop continues until the
     * transport is closed.
     * @param receiver callback invoked for each received datagram
     * @param onClose called when the transport closes (channel no longer open)
     * @param onError called when a receive error occurs
     */
    public void startReceiveLoop(DatagramReceiver receiver, Runnable onClose, Consumer<IOException> onError) {
        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.doReceiveLoop(receiver, onClose, onError);
        });
    }

    private void doReceiveLoop(DatagramReceiver receiver, Runnable onClose, Consumer<IOException> onError) {
        if (this.closed.get() || !this.channel.isOpen()) {
            onClose.run();
            return;
        }

        try {
            if (this.selector.select(100) > 0) {
                this.processSelectedKeys(receiver);
            }
        } catch (IOException e) {
            onError.accept(e);
        } catch (Exception e) {
            onError.accept(new IOException("receive loop error", e));
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.doReceiveLoop(receiver, onClose, onError);
        });
    }

    private void processSelectedKeys(DatagramReceiver receiver) throws IOException {
        Iterator<SelectionKey> it = this.selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();

            if (key.isReadable()) {
                ((Buffer) this.receiveBuffer).clear();
                SocketAddress remoteAddr = this.channel.receive(this.receiveBuffer);
                if (remoteAddr != null) {
                    ((Buffer) this.receiveBuffer).flip();
                    receiver.onDatagram(remoteAddr, this.receiveBuffer);
                }
            }
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────

    /** Returns the underlying DatagramChannel (for write operations and socket configuration). */
    public DatagramChannel getChannel() {
        return this.channel;
    }

    /** Returns the local address of the DatagramChannel. */
    public SocketAddress getLocalAddress() throws IOException {
        return this.channel.getLocalAddress();
    }

    /** Returns whether this transport is open. */
    public boolean isOpen() {
        return !this.closed.get() && this.channel.isOpen();
    }

    // ── Close ──────────────────────────────────────────────────────────

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            try {
                this.selector.close();
            } catch (Exception ignored) {
            }
            try {
                this.channel.close();
            } catch (Exception ignored) {
            }
            try {
                if (ByteBufUtils.CLEANER != null) {
                    ByteBufUtils.CLEANER.freeDirectBuffer(this.receiveBuffer);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this.taskOwner);
    }
}
