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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Socket Server
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class TcpClient implements AutoCloseable {
    private static final Logger                    logger = Logger.getLogger(TcpClient.class);
    private final        SoConfig                  config;
    private final        SocketContext             context;
    private              AsynchronousChannelGroup  channelGroup;
    private              AsynchronousSocketChannel channel;
    private final        ExecutorService           ioExec;
    private final        ExecutorService           worker;

    public TcpClient() {
        this(new SoConfig());
    }

    public TcpClient(SoConfig config) {
        ExecutorService ioExec = config.getIoExecutor();
        if (ioExec == null) {
            ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpClient.class.getClassLoader(), "Cobble-AIO-Thread-%s", true);
            int process = Runtime.getRuntime().availableProcessors();
            ioExec = Executors.newFixedThreadPool(Math.min(process / 2, 2), threadFactory);
            this.ioExec = ioExec;
        } else {
            this.ioExec = null;
        }

        ExecutorService worker = config.getWorkerExecutor();
        if (worker == null) {
            int process = Runtime.getRuntime().availableProcessors();
            ThreadFactory threadFactory = ThreadUtils.threadFactory(TcpClient.class.getClassLoader(), "Cobble-AIO-Workers-%s", true);
            worker = Executors.newFixedThreadPool(process, threadFactory);
            this.worker = worker;
        } else {
            this.worker = null;
        }

        this.config = config;
        this.context = new SocketContext(config, ioExec, worker);
    }

    public Future<NetChannel> connect(InetSocketAddress remoteAddr) throws IOException {
        this.channelGroup = AsynchronousChannelGroup.withThreadPool(this.context.getIoExecutor());
        this.channel = AsynchronousSocketChannel.open(this.channelGroup);

        // config new socket
        SoConfigUtils.configSocket(context.getConfig(), this.channel);

        Future<NetChannel> future = new BasicFuture<>();
        this.channel.connect(remoteAddr, this.context, new ConnectCompletionHandler(this, this.channel, future));
        logger.info("connect to " + remoteAddr);
        return future;
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
        this.channel.close();
        this.channelGroup.shutdown();
        if (this.ioExec != null) {
            this.ioExec.shutdown();
        }
        if (this.worker != null) {
            this.worker.shutdown();
        }
    }
}
