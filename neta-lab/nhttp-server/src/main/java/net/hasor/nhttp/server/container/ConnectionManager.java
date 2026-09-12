/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.container;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.NetChannel;

/**
 * Tracks active TCP connections and enforces connection limits.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li><b>Connection tracking</b>: registers every accepted TCP channel and
 *       unregisters it on close. The {@link #getActiveCount()} is used for
 *       capacity enforcement and metrics reporting.</li>
 *   <li><b>Limit enforcement</b>: {@link #tryRegister} returns {@code false}
 *       when the number of live connections reaches {@code maxConnections} (0 =
 *       unlimited).</li>
 *   <li><b>Idle-connection eviction</b>: {@link #evictIdle} closes connections
 *       that have been inactive for longer than {@code idleTimeoutMillis}; called
 *       by the server's housekeeping timer.</li>
 *   <li><b>Graceful shutdown</b>: {@link #closeAll} signals all connections to
 *       close immediately.</li>
 * </ol>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class ConnectionManager {
    private static final Logger logger = Logger.getLogger(ConnectionManager.class);

    private final int  maxConnections;
    private final long idleTimeoutMillis;

    private final ConcurrentHashMap<Long, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final AtomicInteger                           activeCount = new AtomicInteger(0);

    /**
     * @param maxConnections   maximum simultaneous connections; 0 = unlimited
     * @param idleTimeoutMillis number of milliseconds before an idle connection
     *                         is closed; 0 = never time out
     */
    public ConnectionManager(int maxConnections, long idleTimeoutMillis) {
        this.maxConnections = maxConnections;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    /**
     * Attempts to register a new connection.
     *
     * @param channel the newly accepted channel
     * @return {@code true} if the connection was accepted;
     *         {@code false} if the {@code maxConnections} limit is reached
     */
    public boolean tryRegister(NetChannel channel) {
        if (this.maxConnections > 0 && this.activeCount.get() >= this.maxConnections) {
            return false;
        }
        ConnectionInfo info = new ConnectionInfo(channel);
        ConnectionInfo existing = this.connections.put(channel.getChannelId(), info);
        if (existing == null) {
            this.activeCount.incrementAndGet();
        }
        return true;
    }

    /**
     * Unregisters a connection that has been closed.
     *
     * @param channel the closed channel
     */
    public void unregister(NetChannel channel) {
        ConnectionInfo removed = this.connections.remove(channel.getChannelId());
        if (removed != null) {
            this.activeCount.decrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // Request activity tracking
    // -------------------------------------------------------------------------

    /**
     * Notifies the manager that a request has started on the given channel.
     * Updates the last-active time to prevent idle eviction.
     */
    public void onRequestStarted(NetChannel channel) {
        ConnectionInfo info = this.connections.get(channel.getChannelId());
        if (info != null) {
            info.requestStarted();
        }
    }

    /**
     * Notifies the manager that a request has completed on the given channel.
     */
    public void onRequestCompleted(NetChannel channel) {
        ConnectionInfo info = this.connections.get(channel.getChannelId());
        if (info != null) {
            info.requestCompleted();
        }
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    /** Returns the current number of registered (live) connections. */
    public int getActiveCount() {
        return this.activeCount.get();
    }

    // -------------------------------------------------------------------------
    // Housekeeping
    // -------------------------------------------------------------------------

    /**
     * Closes connections that have been idle longer than the configured
     * {@code idleTimeoutMillis}. Should be called by a periodic housekeeping task.
     * Has no effect when {@code idleTimeoutMillis} is 0.
     */
    public void evictIdle() {
        if (this.idleTimeoutMillis <= 0) {
            return;
        }
        List<NetChannel> toClose = new ArrayList<>();
        for (ConnectionInfo info : this.connections.values()) {
            if (info.isIdle(this.idleTimeoutMillis)) {
                toClose.add(info.channel);
            }
        }
        for (NetChannel ch : toClose) {
            logger.debug("Closing idle connection: " + ch.getChannelId());
            ch.close();
        }
    }

    /**
     * Immediately closes all tracked connections.
     * Used during server shutdown; does not wait for in-flight requests.
     */
    public void closeAll() {
        for (ConnectionInfo info : this.connections.values()) {
            try {
                info.channel.close();
            } catch (Throwable t) {
                logger.warn("Error closing connection " + info.getChannelId(), t);
            }
        }
    }
}
