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
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for {@link SoChannel} implementations that are used in an embedded fashion.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class EmbeddedChannel implements SoChannel<EmbeddedChannel> {
    private static final Logger                    logger = Logger.getLogger(EmbeddedChannel.class);
    private final        long                      channelID;
    private final        long                      createdTime;
    private              long                      lastActiveTime;
    private final        boolean                   asServer;
    private final        EmbeddedSoContext         context;
    //    private static final SocketAddress LOCAL_ADDRESS = new EmbeddedSocketAddress();
    //    private static final SocketAddress REMOTE_ADDRESS = new EmbeddedSocketAddress();
    //
    private final        PipeQueue<Object>         rcvDown;
    private final        PipeQueue<Object>         sndDown;
    protected final      PipeContextImpl           pipeCtx;
    protected final      PipeStack<Object, Object> pipeStack;
    //
    private final        AtomicBoolean             closeStatus;
    private final        Future<EmbeddedChannel>   closeFuture;

    private static class EmbeddedPipeContextImpl extends PipeContextImpl {
        protected EmbeddedPipeContextImpl(EmbeddedChannel channel, SoContext soContext) {
            super(channel, soContext);
        }
    }

    public EmbeddedChannel(boolean asServer, PipeStackFactory stackFactory, EmbeddedSoContext context) {
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
            this.pipeStack = stackFactory.create(this.pipeCtx);
        } catch (Exception e) {
            throw ExceptionUtils.toRuntime(e);
        }

        PipeChainRoot chainRoot = (PipeChainRoot) this.pipeStack;
        if (chainRoot.getListener() == null) {
            chainRoot.bindListener((channel, data) -> {
                this.rcvDown.offerMessage(data);
                this.rcvDown.sndSubmit();
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

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}.
     *
     * @param object the messages to be written
     */
    public <T> void writeRcvUp(T object) {
        try {
            this.lastActiveTime = System.currentTimeMillis();
            Object[] sndDownObj = this.pipeStack.rcvLayer(this.pipeCtx, object);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(Arrays.asList(sndDownObj));
                this.sndDown.sndSubmit();
            }
        } catch (Throwable e) {
            closeNow();
            throw ExceptionUtils.toRuntime(e);
        }
    }

    /**
     * read messages from the RCV_DOWN of this {@link SoChannel}.
     */
    public <T> T readRcvDown() {
        try {
            if (this.rcvDown.hasMore()) {
                return (T) this.rcvDown.takeMessage();
            } else {
                return null;
            }
        } finally {
            this.rcvDown.rcvSubmit();
        }
    }

    /**
     * read messages from the SND_DOWN of this {@link SoChannel}.
     */
    public <T> T readSndDown() {
        try {
            if (this.sndDown.hasMore()) {
                return (T) this.sndDown.takeMessage();
            } else {
                return null;
            }
        } finally {
            this.sndDown.rcvSubmit();
        }
    }

    /**
     * Write messages to the SND_UP of this {@link SoChannel}.
     *
     * @param object the messages to be written
     */
    public <T> void writeSndUp(T object) {
        try {
            Objects.requireNonNull(object);
            Object[] sndDownObj = this.pipeStack.sndLayer(this.pipeCtx, object);
            if (sndDownObj.length != 0) {
                this.sndDown.offerMessage(Arrays.asList(sndDownObj));
                this.sndDown.sndSubmit();
            }
        } catch (Throwable e) {
            closeNow();
            throw ExceptionUtils.toRuntime(e);
        }
    }
}