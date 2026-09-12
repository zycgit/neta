/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import java.util.concurrent.TimeUnit;
import net.hasor.neta.codec.http.HttpContent;

/**
 * Streaming channel for request body data.
 *
 * <p>The IO thread (producer) pushes {@link HttpContent} chunks into this channel via the
 * internal {@code offer()} method. The worker thread (consumer) reads chunks one-by-one by
 * calling {@link #read(long, TimeUnit)}.</p>
 *
 * <p><b>ByteBuf lifecycle:</b> the channel owns the reference counting of buffered chunks.
 * A chunk returned by {@link #read} is released automatically by the channel on the next
 * {@code read()} call or when {@link #close()} is called. Callers must not manually
 * {@code release()} returned chunks.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public interface BodyChannel {

    /**
     * Reads the next body chunk, blocking until data arrives or the timeout elapses.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit for {@code timeout}
     * @return the next {@link HttpContent}, or {@code null} if timed out or the channel was closed
     *         with no more data
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    HttpContent read(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Returns {@code true} if the final {@link net.hasor.neta.codec.http.LastHttpContent} chunk
     * has been offered to this channel (the request body is complete).
     */
    boolean isComplete();

    /**
     * Closes the channel, draining and releasing all buffered {@link HttpContent} chunks.
     * Safe to call from any thread; subsequent {@link #read} calls return {@code null}.
     * This method is idempotent.
     */
    void close();
}
