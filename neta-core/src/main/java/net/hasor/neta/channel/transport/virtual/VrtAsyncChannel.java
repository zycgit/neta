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
package net.hasor.neta.channel.transport.virtual;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
/**
 * In-process implementation of the low-level virtual transport channel.
 * <p>This type is the low-level adapter used by {@link VrtProvider} for connect-mode clients and
 * standalone virtual channels. It never opens an operating-system socket. Instead, it creates an
 * application-facing {@link VrtChannel}, publishes outbound data into the shared {@link SoContext},
 * and lets {@link VrtTransfer} fan that data out to linked peers.
 * <p><b>Lifecycle:</b>
 * <pre>
 *   connectTo(initializer)
 *       -> create a client-side VrtChannel
 *       -> if a target server exists: target.acceptLink(clientSide)
 *       -> the server creates its own VrtChannel and links both sides through VrtTransfer
 *   write(...)
 *       -> pull SoSndData from the send queue
 *       -> wrap it as PlayLoadObject(channel, data, ...)
 *       -> context.trigger(playLoad)
 *       -> VrtTransfer delivers the data to each linked VrtTransferLink
 * </pre>
 * <ul>
 *   <li><b>Addresses:</b> both local and remote addresses are represented by {@link VrtSocketAddress}.
 *       When a target server exists, the local address reuses the target numeric address and marks
 *       itself as a connect-side endpoint.</li>
 *   <li><b>Role selection:</b> {@link AsyncChannel#connectTo(ProtoInitializer, net.hasor.cobble.concurrent.future.Future)}
 *       promotes the exposed {@link VrtChannel} to {@link VrtMode#Client} only when a target server
 *       is resolved; otherwise the configured mode is kept.</li>
 *   <li><b>Close semantics:</b> {@link #close()} is a hard local close. It only flips the open state
 *       and notifies the context; there is no half-close handshake.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see VrtAsyncServerChannel
 * @see VrtSoConfig
 * @see VrtTransfer
 */
class VrtAsyncChannel implements AsyncChannel {
    private static final Logger         logger = Logger.getLogger(VrtAsyncChannel.class);
    private final long                  channelId;
    private final VrtSocketAddress      bindAddr;
    private final VrtSocketAddress      targetAddr;
    private final VrtAsyncServerChannel target;
    private final SoContextService      context;
    private final VrtSoConfig           soConfig;
    private final AtomicBoolean         closeFlag;

    /**
     * Create a virtual asynchronous channel.
     * @param channelId the channel ID
     * @param target the target server channel; null means a standalone virtual channel
     * @param context the runtime context
     * @param targetAddr the target address
     * @param soConfig the channel configuration
     */
    VrtAsyncChannel(long channelId, VrtAsyncServerChannel target, SoContext context, SocketAddress targetAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.bindAddr = new VrtSocketAddress(((VrtSocketAddress) targetAddr).getAddress(), target != null);
        this.targetAddr = (VrtSocketAddress) targetAddr;
        this.target = target;
        this.context = (SoContextService) context;
        this.soConfig = (VrtSoConfig) soConfig;
        this.closeFlag = new AtomicBoolean(false);
    }

    /**
     * Return the virtual transport configuration used by the current channel.
     * @return the virtual transport configuration
     */
    @Override
    public VrtSoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Return the current channel ID.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the local address of the current channel.
     * @return the local virtual address
     */
    @Override
    public VrtSocketAddress getLocalAddress() {
        return this.bindAddr;
    }

    /**
     * Return the remote address of the current channel.
     * @return the remote virtual address
     */
    @Override
    public VrtSocketAddress getRemoteAddress() {
        return this.targetAddr;
    }

    /**
     * Determine whether the current channel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return !this.closeFlag.get();
    }

    /**
     * Close the current virtual asynchronous channel.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("vrtClose(" + this.getChannelId() + ") close.");
        }
        this.closeFlag.set(true);
    }

    /**
     * Establish a virtual connection and create the exposed {@link VrtChannel}.
     * @param initializer the protocol initializer
     * @param future the future used to receive the connection result
     */
    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        // Create the channel.
        VrtChannel channel;
        try {
            if (this.target != null) {
                VrtMode useMode = VrtMode.Client;
                channel = new VrtChannel(this.channelId, new NetMonitor(), null, useMode, initializer, this, this.context);
                this.target.acceptLink(channel);
            } else {
                VrtMode useMode = this.soConfig.getVrtMode();
                channel = new VrtChannel(this.channelId, new NetMonitor(), null, useMode, initializer, this, this.context);
            }
        } catch (Throwable e) {
            logger.error("ERROR: Connect failed, " + e.getMessage());
            future.failed(e);
            return;
        }

        // Initialize the channel.
        try {
            this.context.initChannel(channel, true);
            future.completed(channel);
        } catch (Throwable e) {
            logger.error("ERROR: Connect failed, " + e.getMessage());
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            context.notifyConnectChannelException(this.channelId, true, ee);
            future.failed(e);
        }
    }

    /**
     * Write queued data from the send context into the virtual transport bus.
     * @param channel the framework channel
     * @param wContext the send context
     */
    @Override
    public synchronized void write(NetChannel channel, SoSndContext wContext) {
        while (!wContext.isEmpty()) {
            this.writeOne(channel, wContext.popData(), wContext);
        }
    }

    private void writeOne(NetChannel channel, SoSndData sndData, SoSndContext queuedContext) {
        VrtChannel vrtChannel = (VrtChannel) channel;
        if (!this.isOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("channel is closed.");
            this.context.notifySndChannelException(channel.getChannelId(), true, err);
            sndData.failed(err);
            if (queuedContext != null) {
                this.purgeSndData(err, queuedContext);
            }
            return;
        }

        while (sndData.hasReadable()) {
            Object data = sndData.transferTake();
            PlayLoad playLoad = PlayLoadObject.of(vrtChannel, data, false, true);
            this.context.trigger(playLoad);
        }
        sndData.completed();
    }

    /**
     * Purge remaining queued send data and fail each pending item.
     * @param e the failure cause
     * @param context the send context
     */
    private void purgeSndData(Throwable e, SoSndContext context) {
        while (!context.isEmpty()) {
            SoSndData sndData = context.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(e);
            });
        }
    }

    /**
     * Submit an internal task to the SoTask scheduler.
     * @param task the task to submit
     * @return the task future
     */
    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}
