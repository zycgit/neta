/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.LastHttpContent;
import net.hasor.nhttp.server.connector.BackpressureStrategy;
import net.hasor.nhttp.server.connector.BodyChannel;

/**
 * IO-thread → worker-thread body channel backed by a bounded {@link BlockingQueue}.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li><b>Producer</b> (IO thread): calls {@link #offer} for each incoming
 *       {@link HttpContent} chunk.</li>
 *   <li><b>Consumer</b> (worker thread): calls {@link #read} to receive chunks one
 *       at a time until {@link #isComplete()} is true or {@code null} is returned
 *       (timeout / channel closed).</li>
 * </ul>
 *
 * <h3>Back-pressure</h3>
 * When the queue is full the configured {@link BackpressureStrategy} is invoked.
 * {@link BackpressureStrategy#FAST_FAIL} returns {@code false} immediately; the
 * caller ({@code HttpRequestHandler}) then sends a 503 response and closes the
 * channel. A {@code limitedWait} strategy sleeps on the IO thread for the
 * configured duration and then lets the caller retry once.
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class InternalBodyChannel implements BodyChannel {
    private final BlockingQueue<HttpContent> queue;
    private final BackpressureStrategy       strategy;
    private final NetChannel                 channel;
    private volatile boolean                 complete = false;
    private volatile boolean                 closed   = false;

    public InternalBodyChannel(int capacity, BackpressureStrategy strategy, NetChannel channel) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.strategy = strategy;
        this.channel = channel;
    }

    // -------------------------------------------------------------------------
    // Producer side (IO thread)
    // -------------------------------------------------------------------------

    /**
     * Enqueues an {@link HttpContent} chunk. Called by the IO thread.
     *
     * @param content the chunk to enqueue (ownership is transferred on success;
     *                the chunk is released on failure)
     * @return {@code true} if the chunk was accepted (or the channel is already
     *         closed, in which case the chunk is silently discarded);
     *         {@code false} if the back-pressure strategy rejected the chunk —
     *         the caller must send a 503 response and stop processing
     */
    public boolean offer(HttpContent content) {
        if (this.closed) {
            content.release();
            return true; // silently discard — caller need not error
        }

        // Fast path: queue has space
        if (this.queue.offer(content)) {
            markCompleteIfLast(content);
            return true;
        }

        // Queue is full — ask the back-pressure strategy
        boolean retry = this.strategy.onQueueFull(this.channel, content);
        if (retry) {
            // Strategy waited; give it one more try
            if (this.queue.offer(content)) {
                markCompleteIfLast(content);
                return true;
            }
            // Still full — give up
            content.release();
            return false;
        } else {
            // Fast-fail: reject immediately
            content.release();
            return false;
        }
    }

    private void markCompleteIfLast(HttpContent content) {
        if (content instanceof LastHttpContent) {
            this.complete = true;
        }
    }

    // -------------------------------------------------------------------------
    // Consumer side (worker thread)
    // -------------------------------------------------------------------------

    /**
     * Retrieves the next chunk, waiting up to {@code timeout} {@link TimeUnit}s.
     * Returns {@code null} if the timeout expires, the channel is closed, or the
     * body is already complete.
     */
    @Override
    public HttpContent read(long timeout, TimeUnit unit) throws InterruptedException {
        if (this.closed) {
            return null;
        }
        return this.queue.poll(timeout, unit);
    }

    /**
     * Returns {@code true} when the terminal {@link LastHttpContent} has been
     * enqueued <em>and</em> the queue has been drained by the consumer.
     */
    @Override
    public boolean isComplete() {
        return this.complete && this.queue.isEmpty();
    }

    /**
     * Closes this channel. Pending chunks are released. Subsequent {@link #read}
     * calls return {@code null}; subsequent {@link #offer} calls are silently
     * discarded (to avoid double-release on the IO thread).
     */
    @Override
    public void close() {
        this.closed = true;
        HttpContent item;
        while ((item = this.queue.poll()) != null) {
            item.release();
        }
    }
}
