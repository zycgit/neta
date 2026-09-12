/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

/**
 * Listener for asynchronous processing lifecycle events on an {@link AsyncContext}.
 * All methods have default empty implementations; implement only those you need.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface AsyncListener {

    /** Called when {@link AsyncContext#complete()} is invoked and the response is committed. */
    default void onComplete(AsyncContext context) {
    }

    /** Called when the async operation times out before {@link AsyncContext#complete()} is called. */
    default void onTimeout(AsyncContext context) {
    }

    /** Called if an error occurs during async processing. */
    default void onError(AsyncContext context, Throwable cause) {
    }

    /** Called when {@link ServletRequest#startAsync()} is invoked (re-entry notification). */
    default void onStartAsync(AsyncContext context) {
    }
}
