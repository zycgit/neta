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
import java.io.IOException;
import java.nio.channels.NotYetConnectedException;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * Application-facing TCP {@link NetChannel} implementation.
 * <p>This class binds one {@link TcpAsyncChannel} together with its dedicated
 * {@link TcpRcvCompletionHandler} and {@link TcpSndCompletionHandler}, and exposes the standard
 * Neta channel API to protocol handlers and subscribers.
 * <p>It does not implement the low-level transport mechanics itself. Instead, it owns the handler
 * pair and closes them together when the channel is closed.
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

        // Set the direct reference for the fast receive path and bypass ConcurrentHashMap lookup.
        this.readHandler.setNetChannel(this);

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

    /**
     * Return whether the read side has been shut down.
     * @return true if the read side is shut down
     */
    public boolean isShutdownInput() {
        return ((TcpAsyncChannel) this.asyncChannel).isShutdownInput();
    }

    /**
     * Shut down the read side of the connection without closing the entire channel.
     */
    public void shutdownInput() {
        try {
            ((TcpAsyncChannel) this.asyncChannel).shutdownInput();
        } catch (NotYetConnectedException | IOException e) {
            logger.warn("channel(" + this.getChannelId() + ") shutdownInput, failed " + e.getMessage(), e);
        }
    }
}