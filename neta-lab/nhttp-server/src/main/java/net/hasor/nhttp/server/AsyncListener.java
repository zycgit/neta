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
