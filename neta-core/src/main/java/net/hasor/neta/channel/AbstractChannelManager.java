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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AIO Socket basic
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class AbstractChannelManager {
    private static final Logger          logger = Logger.getLogger(AbstractChannelManager.class);
    protected            ExecutorService ioExec;
    protected final      SoConfig        config;
    protected final      SoContextImpl   context;
    protected final      AtomicBoolean   shutdown;

    public AbstractChannelManager(SoConfig config) {
        this.config = config;
        this.context = new SoContextImpl(config, (NetaSocket) this);
        this.shutdown = new AtomicBoolean(false);
    }

    /** return {@link SoConfig} */
    public SoConfig getConfig() {
        return this.config;
    }

    /** return {@link SoContext} */
    public SoContext getContext() {
        return this.context;
    }

    public final void shutdown() throws IOException {
        if (this.shutdown.compareAndSet(false, true)) {
            // do close
            this.shutdown0(true);

            // waiting close
            long t = System.currentTimeMillis();
            // waiting close accept
            if (this.ioExec != null) {
                this.ioExec.shutdown();
                while (!this.ioExec.isTerminated()) {
                    long cost = System.currentTimeMillis() - t;
                    if (cost > 3000) {
                        t = System.currentTimeMillis();
                        logger.info("shutdown ioExecutor waiting...");
                    }
                    ThreadUtils.sleep(50);
                }
                logger.info("shutdown ioExecutor done.");
            }

            logger.info("service is shutdown.");
        } else {
            logger.error("service already shutdown.");
        }
    }

    protected abstract void shutdown0(boolean now) throws IOException;
}