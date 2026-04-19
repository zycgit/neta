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

import java.net.SocketAddress;

/**
 * Listener for server and request lifecycle events.
 * All methods have default empty implementations; implement only those you need.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ServerListener {

    /** Called after the server has fully started and is accepting connections. */
    default void onServerStarted(NetaHttpServer server) {
    }

    /** Called when a graceful shutdown has been initiated but not yet completed. */
    default void onServerStopping(NetaHttpServer server) {
    }

    /** Called after the server has fully stopped. */
    default void onServerStopped(NetaHttpServer server) {
    }

    /**
     * Called when a request has been received and is about to be dispatched to the worker pool.
     * Invoked on the neta IO thread; keep this method non-blocking.
     */
    default void onRequestReceived(ServletRequest request) {
    }

    /**
     * Called when request processing has completed (successfully or with error).
     *
     * @param request      the completed request
     * @param response     the committed response
     * @param elapsedMillis total wall-clock time from receipt to completion
     */
    default void onRequestCompleted(ServletRequest request, ServletResponse response, long elapsedMillis) {
    }

    /** Called when a request has timed out before completion. */
    default void onRequestTimeout(ServletRequest request) {
    }

    /**
     * Called when a new TCP connection has been established.
     *
     * @param channelId    unique channel identifier
     * @param remoteAddress remote address of the client
     */
    default void onConnectionOpened(long channelId, SocketAddress remoteAddress) {
    }

    /**
     * Called when a TCP connection has been closed.
     *
     * @param channelId    unique channel identifier
     * @param remoteAddress remote address of the client
     */
    default void onConnectionClosed(long channelId, SocketAddress remoteAddress) {
    }
}
