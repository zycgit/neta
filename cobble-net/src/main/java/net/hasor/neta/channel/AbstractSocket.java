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
package net.hasor.neta.channel;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AIO Socket basic
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractSocket implements AutoCloseable {
    private static final Logger          logger = Logger.getLogger(AbstractSocket.class);
    protected            ExecutorService ioExec;
    protected            SoConfig        config;
    protected            SoContextImpl   context;
    protected            AtomicBoolean   inited;

    /** return {@link SoConfig} */
    public SoConfig getConfig() {
        return this.config;
    }

    /** return {@link SoContext} */
    protected SoContext getContext() {
        return this.context;
    }

    protected void initTcp(SoConfig config) {
        ExecutorService ioExec = config.getIoExecutor();
        if (ioExec == null) {
            ThreadFactory threadFactory = ThreadUtils.threadFactory(CobbleSocket.class.getClassLoader(), "Cobble-AIO-Thread-%s", true);
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
                ThreadFactory threadFactory = ThreadUtils.threadFactory(CobbleSocket.class.getClassLoader(), tempName, true);
                return Executors.newFixedThreadPool(process, threadFactory);
            };
        }

        this.config = config;
        this.context = new SoContextImpl(config, ioExec, executorFactory);
        this.inited = new AtomicBoolean(false);
    }

    @Override
    public final void close() throws IOException {
        if (!this.inited.get()) {
            return;
        }

        // do close
        this.close0();

        // waiting close
        long t = System.currentTimeMillis();
        // waiting close accept
        if (this.ioExec != null) {
            this.ioExec.shutdown();
            while (!this.ioExec.isTerminated()) {
                long cost = System.currentTimeMillis() - t;
                if (cost > 3000) {
                    t = System.currentTimeMillis();
                    logger.info("close ioExecutor waiting...");
                }
                ThreadUtils.sleep(50);
            }
            logger.info("close ioExecutor done.");
        }

        logger.info("close done.");
    }

    protected abstract void close0() throws IOException;
}