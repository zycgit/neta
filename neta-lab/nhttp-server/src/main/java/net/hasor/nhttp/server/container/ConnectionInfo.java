/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.container;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.neta.channel.NetChannel;

/**
 * Holds per-connection metadata tracked by {@link ConnectionManager}.
 *
 * <p>All fields are thread-safe so that multiple concurrent requests on the same
 * connection (HTTP/2 multiplexing) can update the active-request counter without
 * coordination.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class ConnectionInfo {
    final NetChannel    channel;
    final long          connectTimeMillis;
    final AtomicInteger activeRequestCount = new AtomicInteger(0);
    final AtomicLong    lastActiveTimeMillis;

    ConnectionInfo(NetChannel channel) {
        this.channel = channel;
        long now = System.currentTimeMillis();
        this.connectTimeMillis = now;
        this.lastActiveTimeMillis = new AtomicLong(now);
    }

    /** Called when a new request starts on this connection. */
    void requestStarted() {
        this.activeRequestCount.incrementAndGet();
        this.lastActiveTimeMillis.set(System.currentTimeMillis());
    }

    /** Called when a request completes on this connection. */
    void requestCompleted() {
        this.activeRequestCount.decrementAndGet();
        this.lastActiveTimeMillis.set(System.currentTimeMillis());
    }

    /** Returns true if no requests have been active for longer than {@code idleMillis}. */
    boolean isIdle(long idleMillis) {
        return this.activeRequestCount.get() == 0 && (System.currentTimeMillis() - this.lastActiveTimeMillis.get()) >= idleMillis;
    }

    long getChannelId() {
        return this.channel.getChannelId();
    }
}
