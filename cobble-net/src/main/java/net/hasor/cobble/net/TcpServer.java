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
package net.hasor.cobble.net;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AIO TCP Server
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class TcpServer implements AutoCloseable {
    private static final Logger                          logger = Logger.getLogger(TcpServer.class);
    private              SoConfig                        config;
    private              SoContextImpl                   context;
    private              AtomicBoolean                   inited;
    private              ExecutorService                 ioExec;
    private              AsynchronousChannelGroup        channelGroup;
    //
    private              AsynchronousServerSocketChannel acceptChannel;

    public TcpServer(SoConfig config) {
        this.initTcpServer(config);
    }

    public TcpServer(SoConfig config, int listenPort) throws IOException {
        this(config, new InetSocketAddress(listenPort));
    }

    public TcpServer(SoConfig config, String listenAddr, int listenPort) throws IOException {
        this(config, new InetSocketAddress(listenAddr, listenPort));
    }

    public TcpServer(SoConfig config, InetSocketAddress inited) throws IOException {
        this.initTcpServer(config);
        this.listen(inited);
    }

    public SoConfig getConfig() {
        return this.config;
    }

    public SoContext getContext() {
        return this.context;
    }

    private void initTcpServer(SoConfig config) {
        ExecutorService ioExec = config.getIoExecutor();
        if (ioExec == null) {
            ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpServer.class.getClassLoader(), "Cobble-AIO-Thread-%s", true);
            int process = Runtime.getRuntime().availableProcessors();
            ioExec = Executors.newFixedThreadPool(Math.min(process / 2, 2), threadFactory);
            this.ioExec = ioExec;
        } else {
            this.ioExec = null;
        }

        SoExecutorFactory executorFactory = config.getTaskExecutorFactory();
        if (executorFactory == null) {
            executorFactory = (cfg, ctxName) -> {
                String tempName = "Cobble[" + StringUtils.getOrDefault("default", ctxName) + "]-AIO-Workers-%s";
                int process = Runtime.getRuntime().availableProcessors();
                ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpServer.class.getClassLoader(), tempName, true);
                return Executors.newFixedThreadPool(process, threadFactory);
            };
        }

        this.config = config;
        this.context = new SoContextImpl(config, ioExec, executorFactory);
        this.inited = new AtomicBoolean(false);
    }

    public TcpServer listen(InetSocketAddress listen) throws IOException {
        if (this.inited.compareAndSet(false, true)) {
            this.channelGroup = AsynchronousChannelGroup.withThreadPool(this.context.getIoExecutor());
            this.acceptChannel = AsynchronousServerSocketChannel.open(this.channelGroup);

            SoConfigUtils.configListen(context.getConfig(), this.acceptChannel);
            this.acceptChannel.bind(listen, 0);
            logger.info("listen at " + listen);

            this.acceptChannel.accept(this.context, new AcceptCompletionHandler(this, this.acceptChannel));
            return this;
        } else {
            throw new IllegalStateException("already listen.");
        }
    }

    @Override
    public void close() throws IOException {
        if (!this.inited.get()) {
            return;
        }

        // do close
        this.close0();

        // waiting close
        long t = System.currentTimeMillis();
        while (!this.channelGroup.isTerminated()) {
            long cost = System.currentTimeMillis() - t;
            if (cost > 3000) {
                t = System.currentTimeMillis();
                logger.info("wait channelGroup close...");
            }
            ThreadUtils.sleep(50);
        }
        logger.info("channelGroup closed.");

        // waiting close accept
        if (this.ioExec != null) {
            while (!this.ioExec.isTerminated()) {
                long cost = System.currentTimeMillis() - t;
                if (cost > 3000) {
                    t = System.currentTimeMillis();
                    logger.info("wait acceptThread close...");
                }
                ThreadUtils.sleep(50);
            }
            logger.info("acceptThread closed.");
        }
    }

    final void close0() throws IOException {
        // close resources
        this.acceptChannel.close();
        this.channelGroup.shutdown();
        if (this.ioExec != null) {
            this.ioExec.shutdown();
        }

        this.context.closeAll("shutdown.");
    }
}
