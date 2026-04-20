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
package net.hasor.neta.channel;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.Futures;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Connected socket channel bound to an application-layer protocol stack.
 * Supports asynchronous {@link #sendData} and {@link #flush} operations, as well as waiting for
 * read timeouts.
 * <pre>
 *  Remote ──► [ByteBuf] ──► Decoder(n) ──► … ──► Decoder(0) ──► Application
 *  Remote ◄── [ByteBuf] ◄── Encoder(n) ◄── … ◄── Encoder(0) ◄── Application
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetChannel extends SoAttrChannel<NetChannel> implements SoChannel<NetChannel> {
    private static final Logger                         logger              = Logger.getLogger(NetChannel.class);
    private static final ByteBuf[]                      EMPTY_BYTEBUF_ARRAY = new ByteBuf[0];
    private static final Map<Thread, Deque<NetChannel>> PIPELINE_CALL_CHAIN = new ConcurrentHashMap<>();
    protected final AsyncChannel                        asyncChannel;
    protected final NetListen                           forListen;
    protected final SoSndContext                        wContext;
    protected final SoContextService                    soContext;
    protected final NetMonitor                          monitor;
    protected final ProtoStackChain                     protoStack;
    protected final AtomicBoolean                       closeStatus;
    protected final Future<NetChannel>                  closeFuture;
    protected final Object                              readTimeoutSyncObj;
    //
    final ProtoContextService protoCtx;
    private final long        channelId;
    private final Object[]    singleRcvBuf = new Object[1]; // reusable 1-element array for single RCV
    protected volatile int    readWaiters;

    /**
     * Create a logical channel bound to the underlying asynchronous transport channel and protocol stack.
     * @param channelId unique channel identifier
     * @param monitor channel monitor
     * @param forListen owning listener, or {@code null} for client channels
     * @param initializer protocol stack initializer
     * @param asyncChannel underlying asynchronous channel
     * @param soContext owning network context
     */
    protected NetChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService soContext) {
        this.channelId = channelId;
        this.asyncChannel = asyncChannel;
        this.forListen = forListen;
        this.monitor = monitor;
        this.readTimeoutSyncObj = new Object();
        this.wContext = new SoSndContext();
        this.soContext = soContext;

        this.protoCtx = new ProtoContextService(this, soContext);
        this.protoStack = this.protoCtx.getChainRoot();
        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();
        initializer.config(this.protoCtx);
    }

    /** Return true when the current thread is executing inside the pipeline call chain of any NetChannel. */
    public static boolean isCurrentThreadInPipeline() {
        Deque<NetChannel> callChain = PIPELINE_CALL_CHAIN.get(Thread.currentThread());
        return callChain != null && !callChain.isEmpty();
    }

    /** Return true when the current thread is executing inside the pipeline call chain of the target channel. */
    public static boolean isCurrentThreadInPipeline(NetChannel channel) {
        Objects.requireNonNull(channel, "channel is null.");
        Deque<NetChannel> callChain = PIPELINE_CALL_CHAIN.get(Thread.currentThread());
        return callChain != null && callChain.contains(channel);
    }

    static void enterPipeline(NetChannel channel) {
        Objects.requireNonNull(channel, "channel is null.");
        PIPELINE_CALL_CHAIN.computeIfAbsent(Thread.currentThread(), key -> new ArrayDeque<>()).push(channel);
    }

    static void exitPipeline(NetChannel channel) {
        Objects.requireNonNull(channel, "channel is null.");
        Thread currentThread = Thread.currentThread();
        Deque<NetChannel> callChain = PIPELINE_CALL_CHAIN.get(currentThread);
        if (callChain == null || callChain.isEmpty()) {
            return;
        }

        if (callChain.peek() == channel) {
            callChain.pop();
        } else {
            callChain.removeFirstOccurrence(channel);
        }

        if (callChain.isEmpty()) {
            PIPELINE_CALL_CHAIN.remove(currentThread);
        }
    }

    /** {@inheritDoc} */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isListen() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public long getCreatedTime() {
        return this.monitor.getCreatedTime();
    }

    /** {@inheritDoc} */
    @Override
    public long getLastActiveTime() {
        return this.monitor.getLastActiveTime();
    }

    /** Return the time when data was last sent. */
    public long getLastSndTime() {
        return this.monitor.getLastSndTime();
    }

    /** Return the time when data was last received. */
    public long getLastRcvTime() {
        return this.monitor.getLastRcvTime();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isServer() {
        return this.forListen != null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClient() {
        return this.forListen == null;
    }

    /** {@inheritDoc} */
    @Override
    public SocketAddress getLocalAddr() {
        return this.asyncChannel.getLocalAddress();
    }

    /** {@inheritDoc} */
    @Override
    public SocketAddress getRemoteAddr() {
        return this.asyncChannel.getRemoteAddress();
    }

    /** {@inheritDoc} */
    @Override
    public SoContext getContext() {
        return this.soContext;
    }

    /** Return the underlying asynchronous socket channel implementation. */
    protected AsyncChannel getAsyncChannel() {
        return this.asyncChannel;
    }

    /** Return the traffic and timing monitor attached to this channel. */
    public NetMonitor getMonitor() {
        return this.monitor;
    }

    /** {@inheritDoc} */
    @Override
    public SoConfig getConfig() {
        return this.asyncChannel.getSoConfig();
    }

    /** {@inheritDoc} */
    @Override
    public <T> T findProtoContext(Class<T> serviceType) {
        return this.protoCtx.context(serviceType);
    }

    /**
     * Navigate the routing tree by path and retrieve an attachment from the target branch context.
     * <p>{@code path} is a sequence of {@code (routerStackName, branchName)} pairs. Each pair
     * identifies which router node to enter and which branch to follow. Because a pipeline may
     * contain multiple router nodes chained or nested together, the caller must provide the full
     * path explicitly.</p>
     * <pre>
     *   Main: [A] → [router1] → [Z]
     *                   │
     *         Branch "tls": [SslDuplexer] → [router2]
     *                                           │
     *                                  Branch "http2": [Http2Handler]
     *   // Read the SslContext stored in the "tls" branch context:
     *   findProtoContextByPath(SslContext.class, "router1", "tls")
     *   // Read the Http2Context stored in the nested "http2" branch context:
     *   findProtoContextByPath(Http2Context.class, "router1", "tls", "router2", "http2")
     * </pre>
     * @param type attachment type to retrieve from the target context
     * @param path alternating {@code (routerStackName, branchName)} pairs; must be non-empty and even-length
     * @return attachment value, or {@code null} if the path cannot be resolved or the target context has no matching value
     * @throws IllegalArgumentException if {@code path} is null, empty, or has odd length
     */
    public <T> T findProtoContextByPath(Class<T> type, String... path) {
        if (path == null || path.length == 0 || path.length % 2 != 0) {
            throw new IllegalArgumentException("path must be a non-empty even-length sequence of (routerStackName, branchName) pairs");
        }
        ProtoContextService ctx = this.protoCtx;
        for (int i = 0; i < path.length; i += 2) {
            String routerName = path[i];
            String branchName = path[i + 1];
            Object router = ctx.getHandler(routerName);
            if (!(router instanceof ProtoRoutingDuplexer)) {
                return null;
            }

            ctx = ((ProtoRoutingDuplexer<?, ?>) router).getBranchCtx(branchName);
            if (ctx == null) {
                return null;
            }
        }
        return ctx.context(type);
    }

    /** Return the {@link NetListen} that accepted this channel. */
    public NetListen getListen() {
        return this.forListen;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClose() {
        return !this.asyncChannel.isOpen() || this.closeStatus.get();
    }

    /** {@inheritDoc} */
    @Override
    public Future<NetChannel> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.soContext.getConfig().isPrintLog()) {
                logger.info("[NET] ch=" + this.channelId + " close()");
            }
            if (this.asyncChannel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelId, this.soContext, false);
                this.soContext.submitSoTask(task, this).onCompleted(f -> {
                    this.closeFuture.completed(this);
                }).onFailed(f -> {
                    this.closeFuture.failed(f.getCause());
                });
            } else {
                this.closeFuture.completed(this);
            }
        }
        return this.closeFuture;
    }

    /** {@inheritDoc} */
    @Override
    public void closeNow() {
        if (this.asyncChannel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            logger.info("channel(" + this.channelId + ") closeNow");
            new SoCloseTask(this.channelId, this.soContext, true).run();
        }
        this.closeFuture.completed(this);
    }

    /** {@inheritDoc} */
    @Override
    public void onClose(SoChannelListener<SoChannel<?>> listener) {
        this.closeFuture.onCompleted(f -> listener.onEvent(this));
    }

    /** Return the number of bytes received. */
    public long getRcvBytes() {
        return this.monitor.getRcvCounterBytes();
    }

    /** Return the number of bytes sent. */
    public long getSndBytes() {
        return this.monitor.getSndCounterBytes();
    }

    /** Feed raw received data into the protocol stack; must be called from a single thread. */
    protected void notifyRcv(Object[] rcvBytes) throws Throwable {
        if (this.readWaiters > 0) {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.notifyAll();
            }
        }

        ChainResult cr = this.protoStack.onRcv(this.protoCtx, null, rcvBytes, null);
        if (cr.data.length > 0) {
            appendSoSndTask(toSoSndData(Futures.buildNoop(), cr.data));
        }
    }

    /** Single-element fast path for {@link #notifyRcv}; reuses a cached array to avoid allocation. */
    public void notifyRcvSingle(Object rcvByte) throws Throwable {
        if (this.readWaiters > 0) {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.notifyAll();
            }
        }

        this.singleRcvBuf[0] = rcvByte;
        try {
            ChainResult cr = this.protoStack.onRcv(this.protoCtx, null, this.singleRcvBuf, null);
            if (cr.data.length > 0) {
                appendSoSndTask(toSoSndData(Futures.buildNoop(), cr.data));
            }
        } finally {
            this.singleRcvBuf[0] = null; // Avoid retaining the reference.
        }
    }

    /**
     * Route an inbound or outbound exception into the error handling flow of the matching pipeline direction.
     * @param isRcv when {@code true}, handle it on the receive side; otherwise on the send side
     * @param e exception to handle
     * @throws Throwable thrown when pipeline error handling itself fails
     */
    protected void notifyError(boolean isRcv, Throwable e) throws Throwable {
        ChainResult cr = isRcv ?//
                this.protoStack.onRcv(this.protoCtx, null, null, e) ://
                this.protoStack.onSnd(this.protoCtx, null, null, e);
        if (cr.data.length > 0) {
            appendSoSndTask(toSoSndData(Futures.buildNoop(), cr.data));
        }
    }

    /** Fire a network event from the current channel into the inbound pipeline. */
    public <T> void fireEvent(Class<T> eventType, T event) {
        this.notifyEvent(true, null, eventType, event);
    }

    /**
     * Deliver a network event toward the specified direction and starting handler.
     * @param isRcv when {@code true}, propagate from the receive side; otherwise from the send side
     * @param stackName starting handler name; when {@code null}, use the default entry for the current direction
     * @param eventType event type
     * @param event event object
     * @param <T> event type
     */
    protected <T> void notifyEvent(boolean isRcv, String stackName, Class<T> eventType, T event) {
        if (isRcv) {
            this.soContext.notifyRcvEvent(this.channelId, stackName, SoEventObject.of(this, eventType, event));
        } else {
            this.soContext.notifySndEvent(this.channelId, stackName, SoEventObject.of(this, eventType, event));
        }
    }

    /**
     * Send data to the remote peer. Network I/O transmission runs asynchronously.
     * <p>The data passes through the application-layer protocol stack.</p>
     */
    public Future<?> sendData(Object writeData) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(new Object[] { writeData }, null);
    }

    /**
     * Send data to the remote peer. Network I/O transmission runs asynchronously.
     * <p>The data passes through the application-layer protocol stack.</p>
     */
    public Future<NetChannel> sendData(Object writeData, String stackName) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(new Object[] { writeData }, stackName);
    }

    /**
     * Send data to the remote peer. Network I/O transmission runs asynchronously.
     * <p>The data passes through the application-layer protocol stack.</p>
     */
    public Future<?> sendData(Object[] writeData) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(writeData, null);
    }

    /**
     * Send data to the remote peer. Network I/O transmission runs asynchronously.
     * <p>The data passes through the application-layer protocol stack.</p>
     */
    public Future<NetChannel> sendData(Object[] writeData, String stackName) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(writeData, stackName);
    }

    /** Flush all pending outbound data through the full protocol stack. */
    public Future<NetChannel> flush() {
        return this.sendOrFlush(null, null);
    }

    /**
     * Flush outbound data toward the remote peer. Network I/O transmission runs asynchronously.
     * <p>The data passes through the application-layer protocol stack.</p>
     */
    public Future<?> flush(String stackName) {
        return this.sendOrFlush(null, stackName);
    }

    /** Bypass the normal entry point and send data directly through the SND pipeline. */
    Future<NetChannel> sendEncoded(Object[] writeData) {
        Future<NetChannel> future = newFutureForSend();
        if (future.isDone()) {
            return future;
        }
        SoSndData sndData = null;
        try {
            synchronized (this) {
                sndData = toSoSndData(future, writeData);
                appendSoSndTask(sndData);
            }
        } catch (Throwable e) {
            logger.error("snd(" + this.channelId + ") sendEncoded failed, " + e.getMessage(), e);
            if (sndData != null) {
                sndData.failed(e);
            } else {
                future.failed(e);
            }
        }
        return future;
    }

    private Future<NetChannel> sendOrFlush(Object[] writeData, String stackName) {
        Future<NetChannel> future = newFutureForSend();
        if (future.isDone()) {
            return future;
        }

        if (NetChannel.isCurrentThreadInPipeline(this)) {
            IllegalStateException ex = new IllegalStateException("snd(" + this.channelId + ") channel.send()/flush() is not allowed while current thread is in this channel's pipeline call chain.");
            logger.error("snd(" + this.channelId + ") illegal reentrant send detected.", ex);
            future.failed(ex);
            return future;
        }

        SoSndData sndData = null;
        try {
            ChainResult cr;
            synchronized (this) {
                cr = this.protoStack.onSnd(this.protoCtx, stackName, writeData, null);
            }
            sndData = toSoSndData(future, cr.data);
            appendSoSndTask(sndData);
        } catch (Throwable e) {
            logger.error("snd(" + this.channelId + ") failed, " + e.getMessage(), e);
            if (sndData != null) {
                sndData.failed(e);
            } else {
                future.failed(e);
            }
        }
        return future;
    }

    private SoSndData toSoSndData(Future<NetChannel> future, Object[] dataArray) {
        if (dataArray.length == 0) {
            return new SoSndData(0, EMPTY_BYTEBUF_ARRAY, future, this);
        }

        // Fast path: when all elements are ByteBuf, reuse dataArray directly.
        boolean allByteBuf = true;
        int sendSize = 0;
        for (int i = 0; i < dataArray.length; i++) {
            Object buf = dataArray[i];
            if (buf instanceof ByteBuf) {
                ByteBuf tmpBuf = (ByteBuf) buf;
                sendSize += tmpBuf.readableBytes();
                tmpBuf.markWriter();
            } else {
                allByteBuf = false;
                break;
            }
        }
        if (allByteBuf) {
            return new SoSndData(sendSize, dataArray, future, this);
        }

        // Slow path: wrap non-ByteBuf elements.
        sendSize = 0;
        Object[] wrap = new Object[dataArray.length];
        for (int i = 0; i < dataArray.length; i++) {
            Object buf = dataArray[i];
            if (buf instanceof byte[]) {
                wrap[i] = ByteBuf.wrap((byte[]) buf);
                sendSize = sendSize + ((byte[]) buf).length;
            } else if (buf instanceof ByteBuffer) {
                wrap[i] = ByteBuf.wrap((ByteBuffer) buf);
                sendSize = sendSize + ((ByteBuffer) buf).remaining();
            } else if (buf instanceof ByteBuf) {
                ByteBuf tmpBuf = (ByteBuf) buf;
                sendSize = sendSize + tmpBuf.readableBytes();
                tmpBuf.markWriter();
                wrap[i] = tmpBuf;
            } else {
                wrap[i] = buf;
            }
        }

        return new SoSndData(sendSize, wrap, future, this);
    }

    private Future<NetChannel> newFutureForSend() {
        Future<NetChannel> future = new BasicFuture<>();
        if (this.protoStack.getSndSlotSize() == 0) {
            logger.info("snd(" + this.channelId + ") the ProtoStackChain slot is full.");
            future.failed(ProtoFullException.INSTANCE);
            return future;
        }

        if (this.closeStatus.get()) {
            future.failed(new SoCloseException("the channel is closed."));
            return future;
        }

        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
        if (this.soContext.getConfig().isPrintLog()) {
            logger.info("snd(" + this.channelId + ") appendSoSndTask, dataSize is " + wTask.getDataSize() + ", closeStatus is " + this.closeStatus.get());
        }

        this.wContext.offer(wTask);
        this.asyncChannel.write(this, this.wContext);
    }

    /**
     * Run the full SND pipeline and enqueue the resulting bytes for sending while bypassing the
     * {@code closeStatus} guard. This method is used by {@link SoCloseTask}, mainly so farewell
     * frames such as TLS {@code close_notify} can still be flushed after the close flow has started.
     */
    void flushForClose() {
        SoSndData sndData = null;
        try {
            ChainResult cr;
            synchronized (this) {
                cr = this.protoStack.onSnd(this.protoCtx, null, null, null);
            }
            sndData = toSoSndData(Futures.buildNoop(), cr.data);
            appendSoSndTask(sndData);
        } catch (Throwable e) {
            logger.error("snd(" + this.channelId + ") flushForClose failed, " + e.getMessage(), e);
            if (sndData != null) {
                sndData.failed(e);
            }
        }
    }

    /**
     * Install a timer that triggers a read timeout if no network data is received within the configured period.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void setReadTimeout() {
        NetListen listen = this.getListen();
        SoConfig config = listen != null ? listen.getConfig() : this.getConfig();
        if (config.getSoReadTimeoutMs() > 0) {
            this.setReadTimeout(config.getSoReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            this.setReadTimeout(6, TimeUnit.SECONDS);
        }
    }

    /**
     * Install a timer that triggers a read timeout if no network data is received within the specified period.
     */
    public void setReadTimeout(int timeout, TimeUnit unit) {
        final class CheckTimeout implements TimerTask {
            private final long lastRcvTime;
            private final long waitTimeMs;

            public CheckTimeout(long lastRcvTime, long waitTimeMs) {
                this.lastRcvTime = lastRcvTime;
                this.waitTimeMs = waitTimeMs;
            }

            @Override
            public void run(Timeout timeout) {
                if (getLastRcvTime() <= this.lastRcvTime) {
                    SoReadTimeoutException readTimeout = new SoReadTimeoutException("no data was received with " + this.waitTimeMs + " milliseconds.");
                    soContext.notifyRcvChannelException(channelId, false, readTimeout);
                }
            }
        }

        long waitTimeMs = unit.toMillis(timeout);
        this.soContext.newTimeout(new CheckTimeout(this.monitor.getLastRcvTime(), waitTimeMs), timeout, unit);
    }

    /**
     * Wait for new data to arrive within the configured SoReadTimeoutMs interval.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive() throws InterruptedException, SoReadTimeoutException {
        NetListen listen = this.getListen();
        SoConfig config = listen != null ? listen.getConfig() : this.getConfig();
        if (config.getSoReadTimeoutMs() > 0) {
            this.waitReceive(config.getSoReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            this.waitReceive(6, TimeUnit.SECONDS);
        }
    }

    /**
     * Wait for new data to arrive within the specified timeout.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive(int timeout, TimeUnit unit) throws InterruptedException, SoReadTimeoutException {
        long waitTimeMs = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        this.readWaiters++;
        try {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.wait(waitTimeMs);

                long cost = System.currentTimeMillis() - startTime;
                if (cost >= waitTimeMs) {
                    throw new SoReadTimeoutException("no data was received with " + waitTimeMs + " milliseconds.");
                }
            }
        } finally {
            this.readWaiters--;
        }
    }

    /**
     * Print the current pipeline state and its backtrace to System.out.
     */
    public void printStackTrace() {
        printStackTrace(System.out);
    }

    /**
     * Print the current pipeline state and its backtrace to the specified stream.
     * @param s {@code PrintStream} used for output
     */
    public void printStackTrace(PrintStream s) {
        SoUtils.printStackTrace(s, this, this.protoStack);
    }
}