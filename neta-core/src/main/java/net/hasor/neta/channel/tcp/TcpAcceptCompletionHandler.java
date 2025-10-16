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
import java.net.SocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * TCP Accept Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAcceptCompletionHandler implements CompletionHandler<AsynchronousSocketChannel, SoContext> {
    private static final Logger                          logger = Logger.getLogger(TcpAcceptCompletionHandler.class);
    private final        NetListen                       forListen;
    private final        AsynchronousServerSocketChannel channel;
    private final        TcpSoConfig                     soConfig;

    TcpAcceptCompletionHandler(NetListen forListen, AsynchronousServerSocketChannel channel, TcpSoConfig soConfig) {
        this.forListen = forListen;
        this.channel = channel;
        this.soConfig = soConfig;
    }

    @Override
    public void completed(AsynchronousSocketChannel result, SoContext attachment) {
        if (this.forListen.isClose()) {
            this.printLog("ERROR: AcceptFailed, listen is closed.");
            IOUtils.closeQuietly(result);
            return;
        }

        // accept the next connection
        this.channel.accept(attachment, this);

        if (this.forListen.isSuspend()) {
            IOUtils.closeQuietly(result);
            this.printLog("ERROR: AcceptFailed, listen is suspend.");
            return;
        }

        if (!this.acceptChannel(result, attachment)) {
            IOUtils.closeQuietly(result);
            return;
        }

        // create channel
        TcpChannel channel;
        try {
            long channelId = ((SoContextService) attachment).nextID();
            SocketAddress remoteAddr = result.getRemoteAddress();
            TcpSoConfigUtils.configSocket(this.soConfig, result);
            channel = this.newChannel(this.forListen, new TcpAsyncChannel(channelId, result, this.forListen.getContext(), remoteAddr, this.soConfig));
        } catch (Throwable e) {
            IOUtils.closeQuietly(result);
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            return;
        }

        // init and start read
        try {
            ((SoContextService) attachment).initChannel(channel, true);
            if (!channel.isShutdownInput()) {
                ((SoContextService) attachment).submitSoTask(new SoDelayTask(0), this).onFinal(f -> {
                    channel.getReadHandler().read();
                });
            }
        } catch (Throwable e) {
            SoConnectException ee = e instanceof SoConnectException ? (SoConnectException) e : new SoConnectException(e.getMessage(), e);
            ((SoContextService) attachment).notifyConnectChannelException(channel.getChannelId(), true, ee);
        }
    }

    @Override
    public void failed(Throwable e, SoContext context) {
        if (e instanceof AsynchronousCloseException && this.forListen.isClose()) {
            return;
        }

        if (this.forListen.getContext().getConfig().isPrintLog()) {
            if (e instanceof SoCloseException) {
                logger.info("ERROR: ListenFailed " + e.getMessage());
            } else {
                logger.error("ERROR: ListenFailed " + e.getMessage(), e);
            }
        }

        this.forListen.closeNow();
    }

    private boolean acceptChannel(AsynchronousSocketChannel result, SoContext attachment) {
        try {
            SocketAddress localAddr = result.getLocalAddress();
            SocketAddress remoteAddr = result.getRemoteAddress();
            if (!((SoContextService) attachment).acceptChannel(remoteAddr)) {
                IOUtils.closeQuietly(result);
                logger.warn("reject(" + this.forListen.getChannelId() + ") R:" + remoteAddr + " -> L:" + localAddr);
                return false;
            } else {
                logger.info("accept(" + this.forListen.getChannelId() + ") R:" + remoteAddr + " -> L:" + localAddr);
                return true;
            }
        } catch (Throwable e) {
            logger.error("ERROR: AcceptFailed, " + e.getMessage(), e);
            return false;
        }
    }

    private void printLog(String msg) {
        if (this.forListen.getContext().getConfig().isPrintLog()) {
            try {
                logger.warn(msg);
            } catch (Exception ignored) {
            }
        }
    }

    protected TcpChannel newChannel(NetListen forListen, TcpAsyncChannel realChannel) throws IOException {
        SoContext context = forListen.getContext();
        NetMonitor monitor = new NetMonitor();
        return new TcpChannel(                                             //
                realChannel.getChannelId(),                                //
                monitor,                                                   //
                forListen,                                                 //
                forListen.getInitializer(),                                //
                realChannel,                                               //
                (SoContextService) context,                                //
                new TcpRcvCompletionHandler(realChannel, context, monitor),//
                new TcpSndCompletionHandler(realChannel, context, monitor) //
        );
    }
}