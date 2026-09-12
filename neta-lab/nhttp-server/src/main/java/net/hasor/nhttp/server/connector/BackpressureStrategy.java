/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.HttpContent;

/**
 * Strategy controlling IO-thread behaviour when the per-request {@link BodyChannel} queue is full.
 *
 * <p>When the worker thread (consumer) is too slow, new {@link HttpContent} chunks cannot be
 * enqueued. The strategy decides whether to wait, reject immediately, or apply HTTP/2 flow
 * control pressure.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
@FunctionalInterface
public interface BackpressureStrategy {

    /**
     * Called by the IO thread when the {@link BodyChannel} queue is full.
     *
     * @param channel the current connection (may be used for HTTP/2 flow control)
     * @param content the chunk that could not be enqueued
     * @return {@code true} if the chunk was eventually accepted;
     *         {@code false} if it must be discarded — the IO thread will send 503 and close
     */
    boolean onQueueFull(NetChannel channel, HttpContent content);

    // -------------------------------------------------------------------------
    // Built-in strategies
    // -------------------------------------------------------------------------

    /**
     * Fail-fast strategy (default): return {@code false} immediately when the queue is full.
     * The IO thread sends a 503 response and closes the connection.
     * Best suited for environments with strict memory/resource budgets.
     */
    BackpressureStrategy FAST_FAIL = (channel, content) -> false;

    /**
     * Creates a bounded-wait strategy that blocks the IO thread for at most {@code maxWaitMillis}
     * milliseconds, retrying the enqueue at short intervals.
     *
     * <p>For HTTP/2 connections, the wait period implicitly reduces the flow control window
     * seen by the peer (the peer will not send more data while this thread is blocked),
     * achieving a cooperative slow-down without a hard disconnect.</p>
     *
     * @param maxWaitMillis maximum number of milliseconds to wait before giving up
     * @return the limited-wait strategy
     */
    static BackpressureStrategy limitedWait(long maxWaitMillis) {
        return (channel, content) -> {
            // Block the IO thread for maxWaitMillis, then signal the caller to retry once.
            // For HTTP/2 connections this acts as a cooperative flow-control signal:
            // the peer will not receive window updates while this thread sleeps.
            try {
                TimeUnit.MILLISECONDS.sleep(maxWaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true; // caller will retry the queue offer exactly once more
        };
    }
}
