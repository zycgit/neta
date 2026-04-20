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
import net.hasor.neta.channel.*;
/**
 * Application-facing virtual channel object.
 * <p>This type is the channel instance exposed to protocol handlers after a virtual client connect
 * or server-side accept completes. It extends the common channel base and also provides manual
 * entry points for injecting receive data and send/receive errors, so that the virtual transport
 * can drive the pipeline without real socket I/O.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtChannel extends AbstractVrtChannel {
    /**
     * Create a virtual channel.
     * @param channelId the channel ID
     * @param monitor the monitor
     * @param forListen the source listener
     * @param vrtMode the virtual channel mode
     * @param initializer the protocol initializer
     * @param asyncChannel the underlying asynchronous channel
     * @param context the runtime context service
     * @throws IOException if an I/O error occurs during creation
     */
    protected VrtChannel(long channelId, NetMonitor monitor, NetListen forListen, VrtMode vrtMode, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, vrtMode, initializer, asyncChannel, context);
    }

    /**
     * Write messages into the RCV_UP direction of the current {@link SoChannel}.
     * <p>The messages are delivered only to the targeted protocol layer.
     * @param object the messages to write
     */
    public void receiveData(Object... object) {
        if (object != null) {
            this.soContext.notifyRcvChannelData(this.getChannelId(), object);
        }
    }

    /**
     * Write an exception into the RCV_UP direction of the current {@link SoChannel}.
     * <p>The exception is delivered only to the targeted protocol layer.
     * @param e the exception to write
     */
    public void receiveError(SoException e) {
        if (e != null) {
            this.soContext.notifyRcvChannelException(this.getChannelId(), true, e);
        }
    }

    /**
     * Write an exception into the SND_UP direction of the current {@link SoChannel}.
     * <p>The exception is delivered only to the targeted protocol layer.
     * @param e the exception to write
     */
    @Deprecated
    public void sendError(SoException e) {
        if (e != null) {
            this.soContext.notifySndChannelException(this.getChannelId(), true, e);
        }
    }
}