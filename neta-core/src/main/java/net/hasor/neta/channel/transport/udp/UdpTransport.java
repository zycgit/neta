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
package net.hasor.neta.channel.transport.udp;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.DefaultSoTask;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.SoDelayTask;
/**
 * Shared datagram I/O engine for UDP and UDP-based protocols.
 * <p>This class owns the underlying {@link DatagramChannel}, the matching {@link Selector}, one
 * reusable direct-memory receive buffer, and a polling receive loop that keeps rescheduling itself
 * through the task system in order to hand raw datagrams back to upper-layer handlers.
 * <p><b>Execution model:</b>
 * <pre>
 *   DatagramChannel + Selector
 *      +--> select()
 *         +--> receive into shared direct ByteBuffer
 *         +--> flip buffer
 *         +--> DatagramReceiver.onDatagram(remoteAddr, data)
 *         +--> schedule the next polling task
 * </pre>
 * <p>The received {@link ByteBuffer} is reused on the next polling round, so callback
 * implementations must consume or copy the data before returning.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2026-02-21
 */
public class UdpTransport implements Closeable {
    private final DatagramChannel  channel;
    private final Selector         selector;
    private final SoContextService context;
    private final ByteBuffer       receiveBuffer;
    private final Object           taskOwner;
    private final AtomicBoolean    closed         = new AtomicBoolean(false);
    private volatile int           selectorPollMs = 100;

    /**
     * Create a UDP transport.
     * @param channel the underlying DatagramChannel
     * @param context the runtime context service
     * @param receiveBufferSize the receive buffer size
     * @param taskOwner the task owner
     * @throws IOException if an I/O error occurs during initialization
     */
    private UdpTransport(DatagramChannel channel, SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        this.channel = channel;
        this.selector = Selector.open();
        this.context = context;
        this.receiveBuffer = ByteBuffer.allocateDirect(receiveBufferSize);
        this.taskOwner = taskOwner;
    }

    // Factory methods

    /**
     * Open a new transport backed by a DatagramChannel.
     * @param context the SoContextService used for task scheduling
     * @param receiveBufferSize the internal receive buffer size in bytes
     * @param taskOwner the task owner passed to SoTask submission
     * @return the newly created UdpTransport
     * @throws IOException if an I/O error occurs during creation
     */
    public static UdpTransport open(SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new UdpTransport(channel, context, receiveBufferSize, taskOwner);
    }

    /**
     * Wrap an existing DatagramChannel as a transport.
     * @param ownerChannelId the ID of the owning server or client channel
     * @param channel the already opened DatagramChannel
     * @param context the SoContextService used for task scheduling
     * @param receiveBufferSize the internal receive buffer size in bytes
     * @param taskOwner the task owner passed to SoTask submission
     * @return the newly created UdpTransport
     * @throws IOException if an I/O error occurs during creation
     */
    public static UdpTransport wrap(long ownerChannelId, DatagramChannel channel, SoContextService context, int receiveBufferSize, Object taskOwner) throws IOException {
        return new UdpTransport(channel, context, receiveBufferSize, taskOwner);
    }

    /**
     * Bind the underlying DatagramChannel to the given address, switch it to non-blocking mode,
     * and register it on the Selector for read events.
     * @param addr the bind address
     * @throws IOException if an I/O error occurs during bind
     */
    public void bind(SocketAddress addr) throws IOException {
        this.channel.bind(addr);
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);
    }

    // Setup

    /**
     * Connect the underlying DatagramChannel to the given remote address, switch it to
     * non-blocking mode, and register it on the Selector for read events.
     * @param addr the remote address
     * @throws IOException if an I/O error occurs during connect
     */
    public void connect(SocketAddress addr) throws IOException {
        this.channel.connect(addr);
        this.channel.configureBlocking(false);
        this.channel.register(this.selector, SelectionKey.OP_READ);
    }

    /**
     * Set the Selector polling interval in milliseconds. The default value is 100.
     * <p>Smaller values reduce packet receive latency at the cost of somewhat higher CPU usage.
     * This method must be called before {@link #startReceiveLoop}.
     * @param ms the polling interval
     */
    public void setSelectorPollMs(int ms) {
        this.selectorPollMs = Math.max(1, ms);
    }

    // Receive loop

    /**
     * Start a task-driven receive loop.
     * <p>Every received datagram is delivered to the given {@link DatagramReceiver} until the
     * transport is closed.
     * @param receiver the callback invoked for each received datagram
     * @param exitSignal the exit signal
     * @param onClose the callback invoked when the transport closes
     * @param onError the callback invoked when a receive error occurs
     */

    public void startReceiveLoop(DatagramReceiver receiver, BooleanSupplier exitSignal, Runnable onClose, Consumer<IOException> onError) {
        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.doReceiveLoop(receiver, exitSignal, onClose, onError);
        });
    }

    /**
     * Execute one iteration of the receive loop body.
     * @param receiver the datagram receiver callback
     * @param exitSignal the exit signal
     * @param onClose the close callback
     * @param onError the error callback
     */
    private void doReceiveLoop(DatagramReceiver receiver, BooleanSupplier exitSignal, Runnable onClose, Consumer<IOException> onError) {
        if (this.closed.get() || !this.channel.isOpen() || exitSignal.getAsBoolean()) {
            onClose.run();
            return;
        }

        try {
            if (this.selector.select(this.selectorPollMs) > 0) {
                this.processSelectedKeys(receiver);
            }
        } catch (IOException e) {
            onError.accept(e);
        } catch (Exception e) {
            onError.accept(new IOException("receive loop error", e));
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            this.doReceiveLoop(receiver, exitSignal, onClose, onError);
        });
    }

    /**
     * Process read-ready events currently selected by the Selector.
     * @param receiver the datagram receiver callback
     * @throws IOException if an I/O error occurs during processing
     */
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

    /**
     * Return the underlying DatagramChannel.
     * <p>This channel can be used by write operations and socket configuration logic.
     * @return the underlying DatagramChannel
     */
    public DatagramChannel getChannel() {
        return this.channel;
    }

    /**
     * Return the local address of the current DatagramChannel.
     * @return the local address
     * @throws IOException if an I/O error occurs while reading the address
     */
    public SocketAddress getLocalAddress() throws IOException {
        return this.channel.getLocalAddress();
    }

    /**
     * Determine whether the transport is still open.
     * @return true if it is open
     */
    public boolean isOpen() {
        return !this.closed.get() && this.channel.isOpen();
    }

    /**
     * Close the transport and release the related resources.
     * @throws IOException if an I/O error occurs while closing
     */
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

    // Close

    /**
     * Submit an internal SoTask.
     * @param task the task to submit
     * @return the corresponding future
     */
    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this.taskOwner);
    }

    /**
     * Callback interface used for received datagrams.
     * <p>The incoming {@code data} ByteBuffer has position 0 and limit equal to the number of
     * bytes received this time. Because the buffer is reused by the next read, implementations
     * must consume or copy the data before returning.
     */
    public interface DatagramReceiver {
        /**
         * Handle one received datagram.
         * @param remoteAddr the source address of the datagram
         * @param data the payload buffer
         * @throws IOException if an I/O error occurs during processing
         */
        void onDatagram(SocketAddress remoteAddr, ByteBuffer data) throws IOException;
    }
}
