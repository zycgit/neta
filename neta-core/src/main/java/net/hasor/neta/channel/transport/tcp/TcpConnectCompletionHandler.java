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
package net.hasor.neta.channel.transport.tcp;
import java.net.SocketAddress;
import java.nio.channels.CompletionHandler;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.AsyncChannel;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.SoConnectException;
import net.hasor.neta.channel.SoContextService;

/**
 * Completion handler for TCP client connection completion.
 * <p>When the connection succeeds, it initializes the channel, starts the read flow, and completes
 * the future. When the connection fails, it notifies the context and marks the future as failed.
 * <p><b>Connect-completion flow:</b>
 * <pre>
 *   AsynchronousSocketChannel.connect(...)
 *                    ▼
 *          completed(result, ctx)
 *       ┌────────────┼───────────────────────────────┐
 *       ▼            ▼                               ▼
 *   context closed   read local/remote addresses     initChannel(...)
 *       └── close channel and return                  ├── start getReadHandler().read()
 *                    ▼                                └── future.completed(channel)
 *             exception during initialization
 *                    └── failed(e, ctx)
 *                           ├── notifyConnectChannelException(...)
 *                           └── future.failed(e)
 * </pre>
 * <p><b>Failure handling:</b> when the connection fails, it is normalized as a connection
 * exception, reported to the context, and the corresponding future is completed with failure.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class TcpConnectCompletionHandler implements CompletionHandler<Void, SoContextService> {
    private static final Logger             logger = Logger.getLogger(TcpConnectCompletionHandler.class);
    private final        TcpChannel         channel;
    private final        AsyncChannel       asyncChannel;
    private final        Future<NetChannel> future;

    /**
     * Create a connect-completion handler.
     * @param channel the corresponding TCP channel
     * @param asyncChannel the underlying asynchronous channel
     * @param future the future used to return the connection result
     */
    TcpConnectCompletionHandler(TcpChannel channel, AsyncChannel asyncChannel, Future<NetChannel> future) {
        this.channel = channel;
        this.asyncChannel = asyncChannel;
        this.future = future;
    }

    /**
     * Initialize the channel and start the read loop after the connection succeeds.
     * @param result the connection result
     * @param context the runtime context
     */
    @Override
    public void completed(Void result, SoContextService context) {
        // Exit immediately when the context is already closed.
        if (context.isClose()) {
            logger.error("ERROR: Connect Failed, context is closed.");
            this.channel.close();
            return;
        }

        try {
            SocketAddress localAddress = this.asyncChannel.getLocalAddress();
            SocketAddress remoteAddress = this.asyncChannel.getRemoteAddress();
            logger.info("connected(" + this.channel.getChannelId() + ") L:" + localAddress + " -> R:" + remoteAddress);

            // Initialize the channel.
            ((SoContextService) this.channel.getContext()).initChannel(this.channel, true);

            // Start the read flow.
            if (!this.channel.isShutdownInput()) {
                this.channel.getReadHandler().read();
            }

            this.future.completed(this.channel);
        } catch (Throwable e) {
            logger.error("ERROR: Connect finish, but onActive failed.");
            this.failed(e, context);
        }
    }

    /**
     * Report the exception and finish the future when the connection fails.
     * @param e the failure cause
     * @param context the runtime context
     */
    @Override
    public void failed(Throwable e, SoContextService context) {
        logger.error("ERROR: Connect failed, " + e.getMessage());
        SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
        context.notifyConnectChannelException(this.channel.getChannelId(), true, ee);
        this.future.failed(e);
    }
}