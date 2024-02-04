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
package net.hasor.neta.handler;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

import java.io.PrintStream;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for {@link SoChannel} implementations that are used in an embedded fashion.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class EmbeddedChannel extends AttributeChannel<EmbeddedChannel> implements NetDuplexChannel<EmbeddedChannel> {
    private final        long                    channelID;
    private final        long                    createdTime;
    private              long                    lastActiveTime;
    private final        boolean                 asServer;
    private final        EmbeddedSoContext       context;
    private static final SocketAddress           LOCAL_ADDRESS  = new EmbeddedSocketAddress();
    private static final SocketAddress           REMOTE_ADDRESS = new EmbeddedSocketAddress();
    //
    private final        PipeQueue<Object>       rcvDown;
    private              Throwable               rcvError;
    private final        PipeQueue<Object>       sndDown;
    private              Throwable               sndError;
    protected final      PipeContext             pipeCtx;
    protected final      Pipeline<?>             pipeline;
    //
    private final        AtomicBoolean           closeStatus;
    private final        Future<EmbeddedChannel> closeFuture;

    private static class EmbeddedPipeContextImpl extends PipeContextImpl {
        protected EmbeddedPipeContextImpl(EmbeddedChannel channel, SoContext soContext) {
            super(channel, soContext);
        }

        @Override
        public Future<?> sendData(Object writeData) {
            EmbeddedChannel channel = (EmbeddedChannel) getChannel();

            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                channel.send(current, writeData);
            } else {
                channel.send(writeData);
            }
            return new BasicFuture<>(this);
        }

        @Override
        public Future<?> flush() {
            EmbeddedChannel channel = (EmbeddedChannel) getChannel();

            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                channel.send(current, ArrayUtils.EMPTY_OBJECT_ARRAY);
            } else {
                channel.send(ArrayUtils.EMPTY_OBJECT_ARRAY);
            }
            return new BasicFuture<>(this);
        }
    }

    public EmbeddedChannel(boolean asServer, EmbeddedInitializer initializer, EmbeddedSoContext context) {
        this.channelID = EmbeddedSoContext.nextID();
        this.createdTime = System.currentTimeMillis();
        this.lastActiveTime = System.currentTimeMillis();
        this.asServer = asServer;
        this.context = context;

        try {
            context.openChannel(this);
            this.pipeCtx = new EmbeddedPipeContextImpl(this, context);
            this.pipeline = initializer.config(this.pipeCtx);
            this.pipeline.onInit(this.pipeCtx);
            this.rcvDown = new PipeQueue<>(-1);
            this.sndDown = new PipeQueue<>(-1);

            PipeChainRoot chainRoot = (PipeChainRoot) this.pipeline;
            chainRoot.bindListener(new PipeListener() {
                @Override
                public void onReceive(SoChannel<?> channel, Object data) {
                    rcvDown.offerMessage(data);
                    rcvDown.sndSubmit();
                }

                @Override
                public void onError(SoChannel<?> channel, Throwable e, boolean isRcv) {
                    if (isRcv) {
                        rcvError = e;
                    } else {
                        sndError = e;
                    }
                }
            });

            this.pipeline.onActive(this.pipeCtx);
        } catch (Throwable e) {
            throw ExceptionUtils.toRuntime(e);
        }

        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public long getCreatedTime() {
        return this.createdTime;
    }

    @Override
    public long getLastActiveTime() {
        return this.lastActiveTime;
    }

    @Override
    public boolean isListen() {
        return false;
    }

    @Override
    public boolean isServer() {
        return this.asServer;
    }

    @Override
    public boolean isClient() {
        return !this.asServer;
    }

    @Override
    public SocketAddress getLocalAddr() {
        return LOCAL_ADDRESS;
    }

    @Override
    public SocketAddress getRemoteAddr() {
        return REMOTE_ADDRESS;
    }

    @Override
    public SoContext getContext() {
        return this.context;
    }

    @Override
    public <T> T findPipeContext(Class<T> serviceType) {
        return this.pipeCtx.context(serviceType);
    }

    @Override
    public Future<EmbeddedChannel> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            this.context.closeChannel(this.channelID, "close");
            this.closeFuture.completed(this);
        }
        return this.closeFuture;
    }

    @Override
    public Future<EmbeddedChannel> closeNow() {
        if (this.closeStatus.compareAndSet(false, true)) {
            this.context.closeChannel(this.channelID, "close");
            this.closeFuture.completed(this);
        }
        return this.closeFuture;
    }

    @Override
    public boolean isClose() {
        return this.closeStatus.get();
    }

    @Override
    public boolean isShutdownInput() {
        return false;
    }

    @Override
    public void shutdownInput() {

    }

    @Override
    public void ignoreReadEofFlag() {

    }

    @Override
    public boolean isShutdownOutput() {
        return false;
    }

    @Override
    public void shutdownOutput() {

    }

    /** Get protocol stack statistics */
    public PipeStatistical getPipeStatistical() {
        return (PipeStatistical) this.pipeline;
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}.
     * @param object the messages to be written
     */
    public void receive(Object... object) {
        this.receiveTo(null, object);
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param pipeName specific protocol layer
     * @param object the messages to be written
     */
    public void receiveTo(String pipeName, Object... object) {
        if (object == null || object.length == 0) {
            object = ArrayUtils.EMPTY_OBJECT_ARRAY;
        }

        try {
            Objects.requireNonNull(object, "object is null.");
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.pipeline.onRcvMessage(this.pipeCtx, pipeName, object);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(sndDownObj);
                this.sndDown.sndSubmit();
            }
        } catch (Throwable e) {
            closeNow();
            throw ExceptionUtils.toRuntime(e);
        }
    }

    /**
     * Write error to the RCV_UP of this {@link SoChannel}.
     * @param e the messages to be written
     */
    public void receiveError(Throwable e) {
        this.receiveError(null, e);
    }

    /**
     * Write error to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void receiveError(String pipeName, Throwable e) {
        if (e == null) {
            return;
        }

        try {
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.pipeline.onRcvError(this.pipeCtx, pipeName, e);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(sndDownObj);
                this.sndDown.sndSubmit();
            }
        } catch (Throwable ee) {
            closeNow();
            throw ExceptionUtils.toRuntime(ee);
        }
    }

    /**
     * read messages from the RCV_DOWN of this {@link SoChannel}.
     */
    public Object readRcv() {
        try {
            if (this.rcvDown.hasMore()) {
                return this.rcvDown.takeMessage();
            } else {
                return null;
            }
        } finally {
            this.rcvDown.rcvSubmit();
        }
    }

    /** read messages from the RCV_DOWN of this {@link SoChannel}. */
    public Object[] readRcvArray() {
        try {
            return this.rcvDown.takeMessage(this.rcvDown.queueSize()).toArray();
        } finally {
            this.rcvDown.rcvSubmit();
        }
    }

    /** read messages limit from the RCV_DOWN of this {@link SoChannel}. */
    public int getRcvSize() {
        return this.rcvDown.queueSize();
    }

    /** Receive data protocol layer error */
    public boolean hasRcvError() {
        return this.rcvError != null;
    }

    /** Get the possible received data protocol layer error */
    public Throwable getRcvError() {
        return this.rcvError;
    }

    /** Clear the RcvError status. */
    public void clearRcvError() {
        this.rcvError = null;
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}.
     * @param object the messages to be written
     */
    public void send(Object... object) {
        this.sendTo(null, object);
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param pipeName specific protocol layer
     * @param object the messages to be written
     */
    public void sendTo(String pipeName, Object... object) {
        if (object == null || object.length == 0) {
            object = ArrayUtils.EMPTY_OBJECT_ARRAY;
        }

        try {
            Objects.requireNonNull(object, "object is null.");
            Object[] sndDownObj = this.pipeline.onSndMessage(this.pipeCtx, pipeName, object);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(sndDownObj);
                this.sndDown.sndSubmit();
            }
        } catch (Throwable e) {
            closeNow();
            throw ExceptionUtils.toRuntime(e);
        }
    }

    /**
     * Write error to the SND_UP of this {@link SoChannel}.
     * @param e the messages to be written
     */
    public void sendError(Throwable e) {
        this.sendError(null, e);
    }

    /**
     * Write error to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void sendError(String pipeName, Throwable e) {
        if (e == null) {
            return;
        }

        try {
            Objects.requireNonNull(e);
            Object[] sndDownObj = this.pipeline.onSndError(this.pipeCtx, pipeName, e);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(sndDownObj);
                this.sndDown.sndSubmit();
            }
        } catch (Throwable ee) {
            closeNow();
            throw ExceptionUtils.toRuntime(ee);
        }
    }

    /** read messages from the SND_DOWN of this {@link SoChannel}. */
    public Object readSnd() {
        try {
            if (this.sndDown.hasMore()) {
                return this.sndDown.takeMessage();
            } else {
                return null;
            }
        } finally {
            this.sndDown.rcvSubmit();
        }
    }

    /** read messages from the SND_DOWN of this {@link SoChannel}. */
    public Object[] readSndArray(int readSize) {
        try {
            return this.sndDown.takeMessage(Math.min(readSize, this.sndDown.queueSize())).toArray();
        } finally {
            this.sndDown.rcvSubmit();
        }
    }

    /** read messages limit from the SND_DOWN of this {@link SoChannel}. */
    public int getSndSize() {
        return this.sndDown.queueSize();
    }

    /** send data protocol layer error */
    public boolean hasSndError() {
        return this.sndError != null;
    }

    /** Get the possible send data protocol layer error */
    public Throwable getSndError() {
        return this.sndError;
    }

    /** Clear the SndError status. */
    public void clearSndError() {
        this.sndError = null;
    }

    /**
     * Prints this pipline status and its backtrace to the System.out.
     */
    public void printStackTrace() {
        printStackTrace(System.out);
    }

    /**
     * Prints this pipline status and its backtrace to the specified print stream.
     * @param s {@code PrintStream} to use for output
     */
    public void printStackTrace(PrintStream s) {
        SoUtils.printStackTrace(s, this, this.pipeline);
    }
}