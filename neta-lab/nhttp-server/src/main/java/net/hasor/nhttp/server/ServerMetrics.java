/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
