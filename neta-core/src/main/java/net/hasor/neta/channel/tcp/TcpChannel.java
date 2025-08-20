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
package net.hasor.neta.channel.tcp;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.nio.channels.NotYetConnectedException;

/**
 * A tcp network channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class TcpChannel extends NetChannel {
    private static final Logger                  logger = Logger.getLogger(TcpChannel.class);
    private final        TcpRcvCompletionHandler readHandler;
    private final        TcpSndCompletionHandler writeHandler;

    TcpChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, TcpAsyncChannel asyncChannel, SoContextService context//
            , TcpRcvCompletionHandler readHandler, TcpSndCompletionHandler writeHandler) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
        this.readHandler = readHandler;
        this.writeHandler = writeHandler;

        this.onClose(c -> {
            IOUtils.closeQuietly(this.readHandler);
            IOUtils.closeQuietly(this.writeHandler);
        });
    }

    TcpRcvCompletionHandler getReadHandler() {
        return this.readHandler;
    }

    TcpSndCompletionHandler getWriteHandler() {
        return this.writeHandler;
    }

    /** Returns whether the read channel is closed. */
    public boolean isShutdownInput() {
        return ((TcpAsyncChannel) this.asyncChannel).isShutdownInput();
    }

    /** Shutdown the connection for reading without closing the channel. */
    public void shutdownInput() {
        try {
            ((TcpAsyncChannel) this.asyncChannel).shutdownInput();
        } catch (NotYetConnectedException | IOException e) {
            logger.warn("channel(" + this.getChannelId() + ") shutdownInput, failed " + e.getMessage(), e);
        }
    }
}