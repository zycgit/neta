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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.hasor.cobble.logging.Logger;
import net.hasor.nhttp.server.AsyncContext;
import net.hasor.nhttp.server.AsyncListener;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * {@link AsyncContext} implementation used when a servlet calls
 * {@link StreamingServletRequest#startAsync()}.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>A servlet calls {@link StreamingServletRequest#startAsync()}, which creates this
 *       object and stores it on the request.  {@link net.hasor.nhttp.server.NetaHttpServer
 *       NetaHttpServer.handleRequest()} detects {@code isAsyncStarted()} after the servlet
 *       returns and skips the normal commit / cleanup.</li>
 *   <li>The application calls {@link #complete()} from any thread.  This commits the
 *       response (if not already committed) and then runs the container cleanup callback
 *       supplied at construction: decrement the {@link RequestManager} concurrency counter,
 *       update the {@link net.hasor.nhttp.server.container.ConnectionManager
 *       ConnectionManager}, fire the request-completed event, and handle HTTP keep-alive.</li>
 *   <li>Alternatively, the application calls {@link #dispatch(String)}, which re-submits the
 *       request to the container's worker pool under a different path.  After that re-dispatch
 *       the cleanup happens automatically.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>{@link #complete()} is idempotent and safe to call from any thread.
 * {@link #addListener} and {@link #setTimeout} are likewise safe from any thread.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class InternalAsyncContext implements AsyncContext {
    private static final Logger logger = Logger.getLogger(InternalAsyncContext.class);

    private final StreamingServletRequest req;
    private final InternalServletResponse resp;
    private final long                    defaultTimeoutMillis;
    private volatile long                 timeoutMillis;
    private final long                    startTimeMillis = System.currentTimeMillis();
    private final AtomicBoolean           completed       = new AtomicBoolean(false);
    private final List<AsyncListener>     listeners       = new CopyOnWriteArrayList<>();

    /**
     * Post-complete callback: decrements the concurrency slot, updates the connection manager,
     * fires events, and handles keep-alive.  Called exactly once, after the response is committed.
     */
    private final Runnable cleanupCallback;

    /**
     * Re-dispatch callback: submits re-execution of the servlet chain at the given path to the
     * worker pool, with the same cleanup logic applied after the re-dispatch completes.
     * Accepts the target path as its argument.
     */
    private final Consumer<String> dispatchCallback;

    /**
     * @param req                 the request that started async (held for {@link #getRequest()})
     * @param resp                the response (held for {@link #getResponse()} and commit)
     * @param defaultTimeoutMillis default timeout in ms (used when {@link #setTimeout} is -1)
     * @param cleanupCallback     runs once after the response is committed in {@link #complete()}
     * @param dispatchCallback    submits a re-dispatch to the given path on the worker pool
     */
    public InternalAsyncContext(StreamingServletRequest req, InternalServletResponse resp, long defaultTimeoutMillis, Runnable cleanupCallback, Consumer<String> dispatchCallback) {
        this.req = req;
        this.resp = resp;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.timeoutMillis = defaultTimeoutMillis;
        this.cleanupCallback = cleanupCallback;
        this.dispatchCallback = dispatchCallback;
    }

    // =========================================================================
    // AsyncContext API
    // =========================================================================

    @Override
    public ServletRequest getRequest() {
        return this.req;
    }

    @Override
    public ServletResponse getResponse() {
        return this.resp;
    }

    @Override
    public void setTimeout(long timeoutMillis) {
        this.timeoutMillis = (timeoutMillis == -1L) ? this.defaultTimeoutMillis : timeoutMillis;
    }

    @Override
    public long getTimeout() {
        return this.timeoutMillis;
    }

    /**
     * Completes async processing: commits the response (if not yet committed) then runs
     * the container cleanup.  Idempotent — safe to call more than once.
     */
    @Override
    public void complete() {
        if (!this.completed.compareAndSet(false, true)) {
            return; // already completed — no-op
        }
        try {
            if (!this.resp.isCommitted()) {
                try {
                    this.resp.commit();
                } catch (IOException e) {
                    logger.warn("Error committing response in AsyncContext.complete()", e);
                }
            }
        } finally {
            runCleanup();
            fireOnComplete();
        }
    }

    /**
     * Re-dispatches the request to the container at the current request URI.
     * The caller must not touch the request or response after this call returns.
     */
    @Override
    public void dispatch() {
        dispatch(this.req.getRequestPath());
    }

    /**
     * Re-dispatches the request to the container at the specified path.
     * The caller must not touch the request or response after this call returns.
     *
     * @param path target path (relative to context root)
     */
    @Override
    public void dispatch(String path) {
        if (this.completed.get()) {
            throw new IllegalStateException("AsyncContext has already been completed");
        }
        this.dispatchCallback.accept(path);
    }

    @Override
    public void addListener(AsyncListener listener) {
        this.listeners.add(listener);
    }

    @Override
    public boolean isCompleted() {
        return this.completed.get();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void runCleanup() {
        try {
            this.cleanupCallback.run();
        } catch (Throwable t) {
            logger.warn("Error in async cleanup callback", t);
        }
    }

    private void fireOnComplete() {
        for (AsyncListener l : this.listeners) {
            try {
                l.onComplete(this);
            } catch (Throwable t) {
                logger.warn("AsyncListener.onComplete() threw an exception", t);
            }
        }
    }

    /**
     * Fires the {@link AsyncListener#onTimeout} event on all registered listeners.
     * Called by the container's timeout watchdog (if implemented).
     */
    public void fireOnTimeout() {
        for (AsyncListener l : this.listeners) {
            try {
                l.onTimeout(this);
            } catch (Throwable t) {
                logger.warn("AsyncListener.onTimeout() threw an exception", t);
            }
        }
    }

    /**
     * Fires the {@link AsyncListener#onError} event on all registered listeners.
     * Called by the container when an error occurs during async processing.
     */
    public void fireOnError(Throwable cause) {
        for (AsyncListener l : this.listeners) {
            try {
                l.onError(this, cause);
            } catch (Throwable t) {
                logger.warn("AsyncListener.onError() threw an exception", t);
            }
        }
    }

    /**
     * Called periodically by the container's timeout watchdog.
     * If the async context has not completed and the configured timeout has elapsed,
     * fires {@link AsyncListener#onTimeout} on all listeners and then calls
     * {@link #complete()} to clean up the request slot.  No-op if already completed
     * or if {@link #getTimeout()} is {@code 0} (disabled).
     */
    public void checkTimeout() {
        if (this.completed.get()) {
            return; // already done — nothing to do
        }
        long timeout = this.timeoutMillis;
        if (timeout <= 0) {
            return; // timeout disabled
        }
        long elapsed = System.currentTimeMillis() - this.startTimeMillis;
        if (elapsed >= timeout) {
            logger.warn("Async context timed out after " + elapsed + " ms (timeout=" + timeout + " ms)");
            fireOnTimeout();
            complete(); // idempotent; commits response and runs container cleanup exactly once
        }
    }

    /**
     * Fires the {@link AsyncListener#onStartAsync} event on all registered listeners.
     * Should be called when {@code startAsync()} is invoked again on a request that is
     * already in async mode (re-entry).
     */
    public void fireOnStartAsync() {
        for (AsyncListener l : this.listeners) {
            try {
                l.onStartAsync(this);
            } catch (Throwable t) {
                logger.warn("AsyncListener.onStartAsync() threw an exception", t);
            }
        }
    }
}
