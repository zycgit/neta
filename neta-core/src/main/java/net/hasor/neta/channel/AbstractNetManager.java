/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.hasor.cobble.logging.Logger;
/**
 * Skeleton base class of {@link NetManager}. It initializes and owns the shared {@link NetConfig},
 * {@link SoContextService}, and shutdown lifecycle state.
 * <p>This class also exposes the subscription APIs inherited by {@link NetManager}, allowing
 * callers to observe pipeline events by channel ID or custom predicates without holding a specific
 * {@link SoChannel} reference.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see NetManager
 * @see SoContextService
 */
public abstract class AbstractNetManager {
    private static final Logger      logger = Logger.getLogger(AbstractNetManager.class);
    protected final NetConfig        config;
    protected final SoContextService context;
    protected final AtomicBoolean    shutdown;

    /**
     * Create the manager skeleton with the given configuration and initialize the shared context.
     * @param config global network configuration
     */
    public AbstractNetManager(NetConfig config) {
        if (!(this instanceof NetManager)) {
            throw new IllegalStateException("AbstractNetManager must be extended by NetManager");
        }
        this.config = config;
        this.context = new SoContextService(config, (NetManager) this);
        this.shutdown = new AtomicBoolean(false);
    }

    /** Return the global configuration shared by all channels. */
    public NetConfig getConfig() {
        return this.config;
    }

    /** Return the shared context used by all channels and listeners. */
    public SoContext getContext() {
        return this.context;
    }

    /**
     * Subscribe to all messages emitted by the specified channel.
     * @param channelId target channel ID
     * @param listener callback invoked for each {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(long channelId, PlayLoadListener listener) {
        return this.context.subscribe(channelId, listener);
    }

    /**
     * Subscribe to all messages emitted by the specified channel using the given delivery mode.
     * @param channelId target channel ID
     * @param mode delivery mode
     * @param listener callback invoked for each {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(long channelId, SubscribeMode mode, PlayLoadListener listener) {
        return this.context.subscribe(channelId, mode, listener);
    }

    /**
     * Subscribe to messages across all channels that match the given predicate.
     * @param select filter predicate
     * @param listener callback invoked for each matching {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener) {
        return this.context.subscribe(select, listener);
    }

    /**
     * Subscribe to messages matching the given predicate using the specified delivery mode.
     * @param select filter predicate
     * @param mode delivery mode
     * @param listener callback invoked for each matching {@link PlayLoad} event
     */
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener) {
        return this.context.subscribe(select, mode, listener);
    }

    /** Gracefully shut down the manager, closing all channels and releasing resources. */
    public final void shutdown() throws IOException {
        if (this.shutdown.compareAndSet(false, true)) {
            // Perform shutdown.
            this.shutdown0(true);

            if (this.config.isPrintLog()) {
                logger.info("service is shutdown.");
            }
        } else {
            logger.error("service already shutdown.");
        }
    }

    /**
     * Execute the underlying shutdown logic.
     * @param now when {@code true}, indicates immediate close
     */
    protected abstract void shutdown0(boolean now) throws IOException;
}
