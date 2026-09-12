/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.logging.Logger;
import net.hasor.nhttp.server.ServerConfig;

/**
 * Manages the lifecycle of in-flight HTTP requests: enforces concurrency limits, dispatches
 * work to the worker thread pool, and supports graceful shutdown.
 *
 * <h3>Concurrency gate</h3>
 * <p>When {@link ServerConfig#getMaxConcurrentRequests()} is greater than zero, {@link #tryDispatch}
 * uses a CAS loop to atomically claim one slot before submitting to the executor. If no slot is
 * available it returns {@code false} immediately; the caller (the IO thread) should then send a 503
 * response without blocking.</p>
 *
 * <h3>Worker executor</h3>
 * <p>If {@link ServerConfig#getExecutor()} is non-null, that executor is used and is <em>not</em>
 * shut down by {@link #shutdown} (ownership stays with the caller). Otherwise a private
 * cached-thread-pool is created and shut down on {@link #shutdown}.</p>
 *
 * <h3>Body-channel cleanup</h3>
 * <p>After the {@link RequestHandler} returns (normally or with an exception), the request's
 * {@link InternalBodyChannel} is always closed to release any unconsumed {@code HttpContent}
 * buffers back to the pool.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class RequestManager {
    private static final Logger logger = Logger.getLogger(RequestManager.class);

    // -------------------------------------------------------------------------
    // RequestHandler callback
    // -------------------------------------------------------------------------

    /**
     * Performs the full request-handling work on a worker thread.
     * Implementations must not throw checked exceptions; any uncaught exception is logged and
     * the request's body channel is always closed after this method returns.
     */
    @FunctionalInterface
    public interface RequestHandler {
        void handle(RequestContext ctx);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final int             maxConcurrentRequests;
    private final ExecutorService executor;
    private final boolean         ownsExecutor;
    private final RequestHandler  handler;
    private final AtomicInteger   activeCount = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@code RequestManager}.
     *
     * @param config  server configuration (reads {@code maxConcurrentRequests} and {@code executor})
     * @param handler callback that does the actual request processing on the worker thread
     */
    public RequestManager(ServerConfig config, RequestHandler handler) {
        this.maxConcurrentRequests = config.getMaxConcurrentRequests();
        this.handler = handler;

        ExecutorService provided = config.getExecutor();
        if (provided != null) {
            this.executor = provided;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Neta-HTTP-Worker");
                t.setDaemon(true);
                return t;
            });
            this.ownsExecutor = true;
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Attempts to dispatch the request to the worker pool.
     *
     * <p>Uses a CAS loop to atomically claim a concurrency slot when a limit is configured,
     * avoiding the TOCTOU race of a separate check-then-increment.</p>
     *
     * @param ctx the request context to process
     * @return {@code true} if the request was successfully queued;
     *         {@code false} if the {@code maxConcurrentRequests} limit was reached or the
     *         executor rejected the task (e.g. server is shutting down) — the caller must
     *         send a 503 response and release any held resources
     */
    public boolean tryDispatch(RequestContext ctx) {
        // --- Claim a concurrency slot (CAS loop to avoid TOCTOU) ---
        if (this.maxConcurrentRequests > 0) {
            int current;
            do {
                current = this.activeCount.get();
                if (current >= this.maxConcurrentRequests) {
                    return false; // limit reached — caller sends 503
                }
            } while (!this.activeCount.compareAndSet(current, current + 1));
        } else {
            this.activeCount.incrementAndGet();
        }

        // --- Submit to the worker executor ---
        try {
            this.executor.execute(() -> {
                try {
                    this.handler.handle(ctx);
                } catch (Throwable t) {
                    logger.warn("Unhandled exception in RequestHandler [" + ctx.channel.getChannelId() + "]", t);
                } finally {
                    // When async processing is active the slot and body channel are owned by the
                    // InternalAsyncContext; releasing them here would corrupt the active count.
                    if (!ctx.asyncStarted) {
                        this.activeCount.decrementAndGet();
                        try {
                            ctx.bodyChannel.close();
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            // Executor rejected (e.g. shutting down) — roll back the counter
            this.activeCount.decrementAndGet();
            logger.warn("Executor rejected task for channel " + ctx.channel.getChannelId(), t);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Async support
    // -------------------------------------------------------------------------

    /**
     * Releases the concurrency slot held by an async request.
     *
     * <p>Called by {@link InternalAsyncContext#complete()} when async processing finishes
     * outside the normal synchronous handler flow.  Also closes the body channel to release
     * any unconsumed {@code HttpContent} buffers.</p>
     *
     * @param ctx the request context whose slot should be released
     */
    public void releaseSlot(RequestContext ctx) {
        this.activeCount.decrementAndGet();
        try {
            ctx.bodyChannel.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /**
     * Submits a task to the worker executor <em>without</em> claiming a new concurrency slot.
     *
     * <p>Used for async re-dispatch ({@link net.hasor.nhttp.server.AsyncContext#dispatch})
     * where the slot was already claimed by the original request and must not be double-counted.</p>
     *
     * @param task the work to execute on the worker pool
     */
    public void executeAsync(Runnable task) {
        try {
            this.executor.execute(task);
        } catch (Throwable t) {
            logger.warn("Executor rejected async task", t);
        }
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    /** Returns the number of requests that are currently being processed by the worker pool. */
    public int getActiveCount() {
        return this.activeCount.get();
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Initiates a graceful shutdown.
     *
     * <p>If this manager owns its executor (i.e. no executor was provided in {@link ServerConfig}):
     * <ol>
     *   <li>Calls {@link ExecutorService#shutdown()} to stop accepting new tasks.</li>
     *   <li>Waits up to {@code timeoutMillis} for in-flight requests to complete.</li>
     *   <li>If the timeout expires, forces a {@link ExecutorService#shutdownNow()} and waits
     *       an additional 3 seconds before giving up.</li>
     * </ol>
     * If the executor was provided externally, this method is a no-op (the caller manages
     * the executor lifecycle).</p>
     *
     * @param timeoutMillis maximum time (ms) to wait for in-flight requests to finish
     */
    public void shutdown(long timeoutMillis) {
        if (!this.ownsExecutor) {
            return;
        }
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.warn("Worker pool did not finish within " + timeoutMillis + " ms; forcing shutdown");
                this.executor.shutdownNow();
                if (!this.executor.awaitTermination(3_000, TimeUnit.MILLISECONDS)) {
                    logger.warn("Worker pool did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
