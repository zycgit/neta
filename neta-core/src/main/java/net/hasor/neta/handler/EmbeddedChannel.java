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

import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for {@link SoChannel} implementations that are used in an embedded fashion.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class EmbeddedChannel extends AttributeChannel<EmbeddedChannel> {
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
    protected final      PipeChainRoot           pipeStack;
    //
    private final        AtomicBoolean           closeStatus;
    private final        Future<EmbeddedChannel> closeFuture;

    private static class EmbeddedPipeContextImpl extends PipeContextImpl {
        protected EmbeddedPipeContextImpl(EmbeddedChannel channel, SoContext soContext) {
            super(channel, soContext);
        }

        @Override
        public Future<?> sendData(Object writeData) {
            EmbeddedChannel channel = (EmbeddedChannel) channel();

            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                channel.writeSndUp(current, writeData);
            } else {
                channel.writeSndUp(writeData);
            }
            return new BasicFuture<>(this);
        }

        @Override
        public Future<?> flush() {
            EmbeddedChannel channel = (EmbeddedChannel) channel();

            String current = this.flash(PipeContext.CURRENT_PIPE_STACK_NAME);
            if (StringUtils.isNotBlank(current)) {
                channel.writeSndUpArray(current, ArrayUtils.EMPTY_OBJECT_ARRAY);
            } else {
                channel.writeSndUpArray(ArrayUtils.EMPTY_OBJECT_ARRAY);
            }
            return new BasicFuture<>(this);
        }
    }

    public EmbeddedChannel(boolean asServer, PipelineFactory stackFactory, EmbeddedSoContext context) {
        this.channelID = EmbeddedSoContext.nextID();
        this.createdTime = System.currentTimeMillis();
        this.lastActiveTime = System.currentTimeMillis();
        this.asServer = asServer;
        this.context = context;

        try {
            context.openChannel(this);
            this.rcvDown = new PipeQueue<>(-1);
            this.sndDown = new PipeQueue<>(-1);
            this.pipeCtx = new EmbeddedPipeContextImpl(this, context);
            this.pipeStack = (PipeChainRoot) stackFactory.create(this.pipeCtx);
        } catch (Throwable e) {
            throw ExceptionUtils.toRuntime(e);
        }

        if (this.pipeStack.getListener() == null) {
            this.pipeStack.bindListener(new PipeListener<Object>() {
                @Override
                public void onReceive(SoChannel<?> channel, Object data) {
                    rcvDown.offerMessage(data);
                    rcvDown.sndSubmit();
                }

                @Override
                public void onReceiveError(SoChannel<?> channel, Throwable e) {
                    rcvError = e;
                }

                @Override
                public void onSend(SoChannel<?> channel) {

                }

                @Override
                public void onSendError(SoChannel<?> channel, Throwable e) {
                    sndError = e;
                }
            });
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

    /** Get protocol stack statistics */
    public PipeStatistical getPipeStatistical() {
        return this.pipeStack;
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}.
     * @param object the messages to be written
     */
    public <T> void writeRcvUp(T object) {
        if (object != null) {
            this.writeRcvUpArray(null, new Object[] { object });
        } else {
            this.writeRcvUpArray(null, ArrayUtils.EMPTY_OBJECT_ARRAY);
        }
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}.
     * @param object the messages to be written
     */
    public <T> void writeRcvUpArray(T[] object) {
        this.writeRcvUpArray(null, object);
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public <T> void writeRcvUp(String pipeName, T object) {
        if (object != null) {
            this.writeRcvUpArray(pipeName, new Object[] { object });
        } else {
            this.writeRcvUpArray(pipeName, ArrayUtils.EMPTY_OBJECT_ARRAY);
        }
    }

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public <T> void writeRcvUpArray(String pipeName, T[] object) {
        try {
            Objects.requireNonNull(object, "object is null.");
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.pipeStack.rcvLayer(this.pipeCtx, pipeName, object);
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
    public void writeRcvUpError(Throwable e) {
        this.writeRcvUpError(null, e);
    }

    /**
     * Write error to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void writeRcvUpError(String pipeName, Throwable e) {
        try {
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.pipeStack.rcvError(this.pipeCtx, pipeName, e);
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
    public Object readRcvDown() {
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

    /**
     * read messages from the RCV_DOWN of this {@link SoChannel}.
     */
    public Object[] readRcvDownArray() {
        try {
            return this.rcvDown.takeMessage(this.rcvDown.queueSize()).toArray();
        } finally {
            this.rcvDown.rcvSubmit();
        }
    }

    /**
     * read messages limit from the RCV_DOWN of this {@link SoChannel}.
     */
    public int getRcvDownSize() {
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
    public <T> void writeSndUp(T object) {
        if (object != null) {
            this.writeSndUpArray(null, new Object[] { object });
        } else {
            this.writeSndUpArray(null, ArrayUtils.EMPTY_OBJECT_ARRAY);
        }
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}.
     * @param object the messages to be written
     */
    public <T> void writeSndUpArray(T[] object) {
        this.writeSndUpArray(null, object);
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public <T> void writeSndUp(String pipeName, T object) {
        if (object != null) {
            this.writeSndUpArray(pipeName, new Object[] { object });
        } else {
            this.writeSndUpArray(pipeName, ArrayUtils.EMPTY_OBJECT_ARRAY);
        }
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public <T> void writeSndUpArray(String pipeName, T[] object) {
        try {
            Objects.requireNonNull(object, "object is null.");
            Object[] sndDownObj = this.pipeStack.sndLayer(this.pipeCtx, pipeName, object);
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
    public void writeSndUpError(Throwable e) {
        this.writeSndUpError(null, e);
    }

    /**
     * Write error to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void writeSndUpError(String pipeName, Throwable e) {
        try {
            Objects.requireNonNull(e);
            Object[] sndDownObj = this.pipeStack.sndError(this.pipeCtx, pipeName, e);
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
     * read messages from the SND_DOWN of this {@link SoChannel}.
     */
    public Object readSndDown() {
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

    /**
     * read messages from the SND_DOWN of this {@link SoChannel}.
     */
    public Object[] readSndDownArray(int readSize) {
        try {
            return this.sndDown.takeMessage(Math.min(readSize, this.sndDown.queueSize())).toArray();
        } finally {
            this.sndDown.rcvSubmit();
        }
    }

    /**
     * read messages limit from the SND_DOWN of this {@link SoChannel}.
     */
    public int getSndDownSize() {
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
}