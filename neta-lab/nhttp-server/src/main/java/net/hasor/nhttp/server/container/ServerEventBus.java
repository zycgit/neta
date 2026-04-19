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
package net.hasor.nhttp.server.container;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.hasor.cobble.logging.Logger;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.ServerListener;
import net.hasor.nhttp.server.ServletRequest;
import net.hasor.nhttp.server.ServletResponse;

/**
 * Maintains the list of {@link ServerListener}s and dispatches lifecycle events
 * to all registered listeners in a fail-safe manner.
 *
 * <p>Uses {@link CopyOnWriteArrayList} so that listeners can be added or removed
 * concurrently without interfering with ongoing event dispatches. Every {@code fire*}
 * method catches and logs any exception thrown by a listener so that one misbehaving
 * listener cannot prevent the rest from receiving the event.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServerEventBus {
    private static final Logger logger = Logger.getLogger(ServerEventBus.class);

    private final List<ServerListener> listeners = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    /**
     * Adds a listener. Adding the same instance more than once has no special
     * handling — the listener will be notified multiple times.
     */
    public void addListener(ServerListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    /** Removes the first occurrence of the given listener. */
    public void removeListener(ServerListener listener) {
        this.listeners.remove(listener);
    }

    // -------------------------------------------------------------------------
    // Server lifecycle events
    // -------------------------------------------------------------------------

    /** Fires {@link ServerListener#onServerStarted}. */
    public void fireServerStarted(NetaHttpServer server) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onServerStarted(server);
            } catch (Throwable t) {
                logger.warn("ServerListener.onServerStarted threw an exception", t);
            }
        }
    }

    /** Fires {@link ServerListener#onServerStopping}. */
    public void fireServerStopping(NetaHttpServer server) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onServerStopping(server);
            } catch (Throwable t) {
                logger.warn("ServerListener.onServerStopping threw an exception", t);
            }
        }
    }

    /** Fires {@link ServerListener#onServerStopped}. */
    public void fireServerStopped(NetaHttpServer server) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onServerStopped(server);
            } catch (Throwable t) {
                logger.warn("ServerListener.onServerStopped threw an exception", t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Request lifecycle events
    // -------------------------------------------------------------------------

    /** Fires {@link ServerListener#onRequestReceived}. */
    public void fireRequestReceived(ServletRequest request) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onRequestReceived(request);
            } catch (Throwable t) {
                logger.warn("ServerListener.onRequestReceived threw an exception", t);
            }
        }
    }

    /** Fires {@link ServerListener#onRequestCompleted}. */
    public void fireRequestCompleted(ServletRequest request, ServletResponse response, long elapsedMillis) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onRequestCompleted(request, response, elapsedMillis);
            } catch (Throwable t) {
                logger.warn("ServerListener.onRequestCompleted threw an exception", t);
            }
        }
    }

    /** Fires {@link ServerListener#onRequestTimeout}. */
    public void fireRequestTimeout(ServletRequest request) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onRequestTimeout(request);
            } catch (Throwable t) {
                logger.warn("ServerListener.onRequestTimeout threw an exception", t);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle events
    // -------------------------------------------------------------------------

    /** Fires {@link ServerListener#onConnectionOpened}. */
    public void fireConnectionOpened(long channelId, SocketAddress remoteAddress) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onConnectionOpened(channelId, remoteAddress);
            } catch (Throwable t) {
                logger.warn("ServerListener.onConnectionOpened threw an exception", t);
            }
        }
    }

    /** Fires {@link ServerListener#onConnectionClosed}. */
    public void fireConnectionClosed(long channelId, SocketAddress remoteAddress) {
        for (ServerListener listener : this.listeners) {
            try {
                listener.onConnectionClosed(channelId, remoteAddress);
            } catch (Throwable t) {
                logger.warn("ServerListener.onConnectionClosed threw an exception", t);
            }
        }
    }
}
