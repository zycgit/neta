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
 * Read-only view of runtime server metrics.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ServerMetrics {

    /** Returns the number of currently active TCP connections. */
    int getActiveConnections();

    /** Returns the number of requests currently being processed. */
    int getActiveRequests();

    /** Returns the total number of requests received since server start. */
    long getTotalRequests();

    /** Returns the total number of requests that timed out. */
    long getTimedOutRequests();

    /** Returns the total number of requests rejected (due to overload or shutdown). */
    long getRejectedRequests();

    /** Returns the number of currently active WebSocket connections. */
    int getActiveWebSockets();

    /** Returns the number of currently active HTTP sessions. */
    int getActiveSessions();
}
