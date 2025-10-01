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
package net.hasor.neta.channel.virtual;
import net.hasor.neta.channel.*;

import java.io.IOException;

/**
 * virtual channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtChannel extends NetChannel {
    private final VrtMode vrtMode;

    VrtChannel(long channelId, NetMonitor monitor, NetListen forListen, VrtMode vrtMode, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
        this.vrtMode = vrtMode;
    }

    @Override
    public boolean isClient() {
        return this.vrtMode == VrtMode.Client;
    }

    @Override
    public boolean isServer() {
        return this.vrtMode == VrtMode.Server;
    }

    // trigger Input/Output

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public void onReceive(Object... object) {
        if (object != null) {
            this.context.notifyRcvChannelData(this.getChannelId(), object);
        }
    }

    /**
     * Write error to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void onReceiveError(SoException e) {
        if (e != null) {
            this.context.notifyRcvChannelException(this.getChannelId(), true, e);
        }
    }

    /**
     * Write error to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void onSendError(SoException e) {
        if (e != null) {
            this.context.notifySndChannelException(this.getChannelId(), true, e);
        }
    }
}