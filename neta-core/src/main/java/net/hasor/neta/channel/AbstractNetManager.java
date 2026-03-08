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
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.hasor.cobble.logging.Logger;

/**
 * Base class for {@link NetManager}, holding the shared {@link NetConfig}, {@link SoContextService}
 * and lifecycle state. Subclasses implement the actual protocol providers and shutdown logic.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class AbstractNetManager {
    private static final Logger           logger = Logger.getLogger(AbstractNetManager.class);
    protected final      NetConfig        config;
    protected final      SoContextService context;
    protected final      AtomicBoolean    shutdown;

    public AbstractNetManager(NetConfig config) {
        if (!(this instanceof NetManager)) {
            throw new IllegalStateException("AbstractNetManager must be extended by NetManager");
        }
        this.config = config;
        this.context = new SoContextService(config, (NetManager) this);
        this.shutdown = new AtomicBoolean(false);
    }

    /** Returns the global configuration shared by all channels. */
    public NetConfig getConfig() {
        return this.config;
    }

    /** Returns the shared socket context that manages all channels and listeners. */
    public SoContext getContext() {
        return this.context;
    }

    /**
     * Subscribes to all messages (inbound and outbound) for the specified channel.
     * @param channelId the target channel ID
     * @param listener callback invoked for each {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(long channelId, PlayLoadListener listener) {
        return this.context.subscribe(channelId, listener);
    }

    /**
     * Subscribes to all messages for the specified channel using the given delivery mode.
     * @param channelId the target channel ID
     * @param mode delivery mode
     * @param listener callback invoked for each {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(long channelId, SubscribeMode mode, PlayLoadListener listener) {
        return this.context.subscribe(channelId, mode, listener);
    }

    /**
     * Subscribes to messages that match the given predicate across all channels.
     * @param select filter predicate
     * @param listener callback invoked for each matching {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener) {
        return this.context.subscribe(select, listener);
    }

    /**
     * Subscribes to messages that match the given predicate using the specified delivery mode.
     * @param select filter predicate
     * @param mode delivery mode
     * @param listener callback invoked for each matching {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener) {
        return this.context.subscribe(select, mode, listener);
    }

    /** Gracefully shuts down the manager, closing all channels and releasing resources. */
    public final void shutdown() throws IOException {
        if (this.shutdown.compareAndSet(false, true)) {
            // do close
            this.shutdown0(true);

            logger.info("service is shutdown.");
        } else {
            logger.error("service already shutdown.");
        }
    }

    protected abstract void shutdown0(boolean now) throws IOException;
}