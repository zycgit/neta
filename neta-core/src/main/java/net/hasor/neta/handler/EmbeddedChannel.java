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
public class EmbeddedChannel extends AttributeChannel<EmbeddedChannel> implements SoChannel<EmbeddedChannel> {
    private final        long                    channelID;
    private final        long                    createdTime;
    private              long                    lastActiveTime;
    private final        boolean                 asServer;
    private final        EmbeddedSoContext       context;
    private final        SoConfig                soConfig;
    private static final SocketAddress           LOCAL_ADDRESS  = new EmbeddedSocketAddress();
    private static final SocketAddress           REMOTE_ADDRESS = new EmbeddedSocketAddress();
    //
    private final        ProtoQueue<Object>      rcvDown;
    private              Throwable               rcvError;
    private final        ProtoQueue<Object>      sndDown;
    private              Throwable               sndError;
    protected final      ProtoContext            protoCtx;
    protected final      ProtoStack<?>           protoStack;
    //
    private final        AtomicBoolean           closeStatus;
    private final        Future<EmbeddedChannel> closeFuture;

    private static class EmbeddedProtoContextImpl extends ProtoContextService {
        protected EmbeddedProtoContextImpl(EmbeddedChannel channel, SoContext soContext) {
            super(channel, soContext);
        }

        @Override
        public Future<?> sendData(Object writeData) {
            EmbeddedChannel channel = (EmbeddedChannel) getChannel();

            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                channel.sendTo(current, writeData);
            } else {
                channel.sendTo(null, writeData);
            }
            return new BasicFuture<>(this);
        }

        @Override
        public Future<?> flush() {
            EmbeddedChannel channel = (EmbeddedChannel) getChannel();

            String current = this.flash(ProtoContext.CURRENT_PROTO_STACK_NAME);
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
        this.soConfig = new SoConfig("embedded") {
        };

        try {
            context.openChannel(this);
            this.protoCtx = new EmbeddedProtoContextImpl(this, context);
            this.protoStack = initializer.config(this.protoCtx);
            this.protoStack.onInit(this.protoCtx);
            this.rcvDown = new ProtoQueue<>(-1);
            this.sndDown = new ProtoQueue<>(-1);

            ProtoChainRoot chainRoot = (ProtoChainRoot) this.protoStack;
            chainRoot.bindListener(new ProtoListener() {
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

            this.protoStack.onActive(this.protoCtx);
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
    public SoConfig getConfig() {
        return this.soConfig;
    }

    @Override
    public <T> T findProtoContext(Class<T> serviceType) {
        return this.protoCtx.context(serviceType);
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
    public void closeNow() {
        if (this.closeStatus.compareAndSet(false, true)) {
            this.context.closeChannel(this.channelID, "close");
            this.closeFuture.completed(this);
        }
    }

    @Override
    public void onClose(SoCloseListener<SoChannel<?>> listener) {
        this.closeFuture.onCompleted(f -> listener.onClose(this));
    }

    @Override
    public boolean isClose() {
        return this.closeStatus.get();
    }

    /** Get protocol stack statistics */
    public ProtoStatistical getStatistical() {
        return (ProtoStatistical) this.protoStack;
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
     * @param stackName specific protocol layer
     * @param object the messages to be written
     */
    public void receiveTo(String stackName, Object... object) {
        if (object == null || object.length == 0) {
            object = ArrayUtils.EMPTY_OBJECT_ARRAY;
        }

        try {
            Objects.requireNonNull(object, "object is null.");
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.protoStack.onRcvMessage(this.protoCtx, stackName, object);
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
    public void receiveError(String stackName, Throwable e) {
        if (e == null) {
            return;
        }

        try {
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.protoStack.onRcvError(this.protoCtx, stackName, e);
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
    public int getRcvQueueSize() {
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
     * @param stackName specific protocol layer
     * @param object the messages to be written
     */
    public void sendTo(String stackName, Object... object) {
        if (object == null || object.length == 0) {
            object = ArrayUtils.EMPTY_OBJECT_ARRAY;
        }

        try {
            Objects.requireNonNull(object, "object is null.");
            Object[] sndDownObj = this.protoStack.onSndMessage(this.protoCtx, stackName, object);
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
    public void sendError(String stackName, Throwable e) {
        if (e == null) {
            return;
        }

        try {
            Objects.requireNonNull(e);
            Object[] sndDownObj = this.protoStack.onSndError(this.protoCtx, stackName, e);
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
    public int getSndQueueSize() {
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
        SoUtils.printStackTrace(s, this, this.protoStack);
    }
}