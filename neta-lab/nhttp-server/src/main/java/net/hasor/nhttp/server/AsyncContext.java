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
package net.hasor.nhttp.server;

/**
 * Context for managing asynchronous servlet processing.
 * <p>Obtained by calling {@link ServletRequest#startAsync()}, which suspends the normal
 * request lifecycle. The request and response remain open until {@link #complete()} is called
 * from any thread.</p>
 *
 * <p>Lifecycle:</p>
 * <pre>
 *   startAsync() → [do async work in another thread] → complete()
 *              ↘ or dispatch(path) → re-enter container
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 */
public interface AsyncContext {

    /** Returns the request associated with this async context. */
    ServletRequest getRequest();

    /** Returns the response associated with this async context. */
    ServletResponse getResponse();

    /**
     * Sets the async timeout in milliseconds.
     * <ul>
     *   <li>0 = no timeout</li>
     *   <li>-1 = use server default ({@code ServerConfig.requestTimeoutMillis})</li>
     * </ul>
     */
    void setTimeout(long timeoutMillis);

    /** Returns the currently configured timeout in milliseconds. */
    long getTimeout();

    /**
     * Completes the async processing: commits the response, releases the concurrency semaphore,
     * and closes the BodyChannel. This method is idempotent and may be called from any thread.
     */
    void complete();

    /**
     * Re-dispatches the request to the container using the current request URI.
     * The full filter chain is re-executed with {@link DispatcherType#ASYNC}.
     * The caller must not touch request/response after this call.
     */
    void dispatch();

    /**
     * Re-dispatches the request to the container at the specified path.
     * The full filter chain is re-executed with {@link DispatcherType#ASYNC}.
     * The caller must not touch request/response after this call.
     *
     * @param path target path (relative to context root)
     */
    void dispatch(String path);

    /** Adds an async lifecycle listener. */
    void addListener(AsyncListener listener);

    /** Returns {@code true} if {@link #complete()} has already been called. */
    boolean isCompleted();
}
