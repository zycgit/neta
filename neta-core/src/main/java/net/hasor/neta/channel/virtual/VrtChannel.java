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
import java.io.IOException;
import net.hasor.neta.channel.*;

/**
 * Application-facing virtual channel facade.
 * <p>This is the channel instance exposed to protocol handlers after a virtual
 * client connect or server-side accept completes. It extends the common channel
 * base but also exposes manual receive/send error injection methods so that the
 * virtual transport can drive the protocol pipeline without any real socket I/O.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class VrtChannel extends AbstractVrtChannel {
    protected VrtChannel(long channelId, NetMonitor monitor, NetListen forListen, VrtMode vrtMode, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        super(channelId, monitor, forListen, vrtMode, initializer, asyncChannel, context);
    }

    // trigger Input/Output

    /**
     * Write messages to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param object the messages to be written
     */
    public void receiveData(Object... object) {
        if (object != null) {
            this.soContext.notifyRcvChannelData(this.getChannelId(), object);
        }
    }

    /**
     * Write error to the RCV_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void receiveError(SoException e) {
        if (e != null) {
            this.soContext.notifyRcvChannelException(this.getChannelId(), true, e);
        }
    }

    /**
     * Write error to the SND_UP of this {@link SoChannel}, the message will only be sent to the specific protocol layer
     * @param e the messages to be written
     */
    public void sendError(SoException e) {
        if (e != null) {
            this.soContext.notifySndChannelException(this.getChannelId(), true, e);
        }
    }
}