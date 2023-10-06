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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Socket Server
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class TcpServer implements AutoCloseable {
    private static final Logger                          logger = Logger.getLogger(TcpServer.class);
    private final        SoConfig                        config;
    private final        SocketContext                   context;
    private              AsynchronousChannelGroup        channelGroup;
    private              AsynchronousServerSocketChannel acceptChannel;
    private final        ExecutorService                 ioExec;
    private final        ExecutorService                 worker;

    public TcpServer() {
        this(new SoConfig());
    }

    public TcpServer(SoConfig config) {
        ExecutorService ioExec = config.getIoExecutor();
        if (ioExec == null) {
            ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpServer.class.getClassLoader(), "Cobble-AIO-Thread-%s", true);
            int process = Runtime.getRuntime().availableProcessors();
            ioExec = Executors.newFixedThreadPool(Math.min(process / 2, 2), threadFactory);
            this.ioExec = ioExec;
        } else {
            this.ioExec = null;
        }

        ExecutorService worker = config.getWorkerExecutor();
        if (worker == null) {
            int process = Runtime.getRuntime().availableProcessors();
            ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpServer.class.getClassLoader(), "Cobble-AIO-Workers-%s", true);
            worker = Executors.newFixedThreadPool(process, threadFactory);
            this.worker = worker;
        } else {
            this.worker = null;
        }

        this.config = config;
        this.context = new SocketContext(config, ioExec, worker);
    }

    public TcpServer listen(InetSocketAddress listen) throws IOException {
        this.channelGroup = AsynchronousChannelGroup.withThreadPool(this.context.getIoExecutor());
        this.acceptChannel = AsynchronousServerSocketChannel.open(this.channelGroup);

        SoConfigUtils.configListen(context.getConfig(), this.acceptChannel);
        this.acceptChannel.bind(listen, 0);
        logger.info("listen at " + listen);

        this.acceptChannel.accept(this.context, new AcceptCompletionHandler(this, this.acceptChannel));
        return this;
    }

    @Override
    public void close() throws IOException {
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

        // waiting close worker
        if (this.worker != null) {
            while (!this.worker.isTerminated()) {
                long cost = System.currentTimeMillis() - t;
                if (cost > 3000) {
                    t = System.currentTimeMillis();
                    logger.info("wait workerThread close...");
                }
                ThreadUtils.sleep(50);
            }
            logger.info("workerThread closed.");
        }
    }

    final void close0() throws IOException {
        // close resources
        this.acceptChannel.close();
        this.channelGroup.shutdown();
        if (this.ioExec != null) {
            this.ioExec.shutdown();
        }
        if (this.worker != null) {
            this.worker.shutdown();
        }
    }
}
