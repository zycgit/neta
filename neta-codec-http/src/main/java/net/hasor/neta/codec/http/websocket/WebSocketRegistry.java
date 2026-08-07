/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.websocket;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.routing.PartitionKey;
/**
 * Registry that stores websocket contexts by endpoint key.
 * <p>
 * Phase 1 uses the connection-scope key for HTTP/1.x and reserves stream-scope
 * keys for HTTP/2 and newer transports.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-08
 */
public final class WebSocketRegistry {
    private static final String                                           CLOSE_CLEANUP_BOUND_KEY = WebSocketRegistry.class.getName() + ".closeCleanupBound";
    private volatile WebSocketContext                                     connectionContext;
    private volatile ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> streamContexts;

    private WebSocketRegistry() {
    }

    /**
     * Resolve the ready websocket context using the default connection-scope endpoint key.
     * @param context protocol context
     * @return ready websocket context, or {@code null}
     */
    public static WebSocketContext resolve(ProtoContext context) {
        WebSocketRegistryKey endpointKey = resolveEndpointKey(context);
        WebSocketContext webSocketContext = resolve(context, endpointKey);
        if (webSocketContext == null && endpointKey != null && !endpointKey.isConnectionScope()) {
            return resolve(context, WebSocketRegistryKey.connectionScope());
        }
        return webSocketContext;
    }

    /**
     * Resolve the ready websocket context using the specified endpoint key.
     * @param context protocol context
     * @param endpointKey endpoint key
     * @return ready websocket context, or {@code null}
     */
    public static WebSocketContext resolve(ProtoContext context, WebSocketRegistryKey endpointKey) {
        WebSocketRegistry registry = get(context);
        if (registry == null) {
            return null;
        }

        WebSocketRegistryKey key = endpointKey != null ? endpointKey : WebSocketRegistryKey.connectionScope();
        WebSocketContext webSocketContext = registry.get(key);
        return webSocketContext != null && webSocketContext.isReady() ? webSocketContext : null;
    }

    /**
     * Resolve the ready websocket context using the default connection-scope endpoint key.
     * @param channel channel to inspect
     * @return ready websocket context, or {@code null}
     */
    public static WebSocketContext resolve(SoChannel<?> channel) {
        WebSocketRegistryKey endpointKey = resolveEndpointKey(channel);
        WebSocketContext webSocketContext = resolve(channel, endpointKey);
        if (webSocketContext == null && endpointKey != null && !endpointKey.isConnectionScope()) {
            return resolve(channel, WebSocketRegistryKey.connectionScope());
        }
        return webSocketContext;
    }

    /**
     * Resolve the ready websocket context using the specified endpoint key.
     * @param channel channel to inspect
     * @param endpointKey endpoint key
     * @return ready websocket context, or {@code null}
     */
    public static WebSocketContext resolve(SoChannel<?> channel, WebSocketRegistryKey endpointKey) {
        WebSocketRegistry registry = get(channel);
        if (registry == null) {
            return null;
        }

        WebSocketRegistryKey key = endpointKey != null ? endpointKey : WebSocketRegistryKey.connectionScope();
        WebSocketContext webSocketContext = registry.get(key);
        return webSocketContext != null && webSocketContext.isReady() ? webSocketContext : null;
    }

    //

    /**
     * Bind a websocket context to the specified endpoint in the current registry.
     * @param context protocol context
     * @param key endpoint key
     * @param wsContext websocket context
     */
    static void bind(ProtoContext context, WebSocketRegistryKey key, WebSocketContext wsContext) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null.");
        }

        WebSocketRegistry registry = ensure(context);
        registry.bind(key, wsContext);
        if (!key.isConnectionScope()) {
            bindCloseCleanup(context, registry);
        }
    }

    /**
     * Remove the websocket context bound to the specified endpoint from the current registry.
     * @param context protocol context
     * @param key endpoint key
     * @return removed websocket context, or {@code null}
     */
    static WebSocketContext remove(ProtoContext context, WebSocketRegistryKey key) {
        if (context == null || key == null) {
            return null;
        }

        WebSocketRegistry registry = get(context);
        if (registry == null) {
            return null;
        }
        return registry.remove(key);
    }

    //
    //
    //
    //
    //

    /**
     * Bind a websocket context to the specified endpoint.
     * @param endpointKey endpoint key
     * @param webSocketContext websocket context
     */
    void bind(WebSocketRegistryKey endpointKey, WebSocketContext webSocketContext) {
        if (endpointKey == null) {
            throw new IllegalArgumentException("endpointKey must not be null.");
        }
        if (webSocketContext == null) {
            throw new IllegalArgumentException("webSocketContext must not be null.");
        }
        if (endpointKey.isConnectionScope()) {
            this.connectionContext = webSocketContext;
        } else {
            this.streamContexts().put(endpointKey, webSocketContext);
        }
    }

    /**
     * Remove the websocket context bound to the specified endpoint.
     * @param endpointKey endpoint key
     * @return removed websocket context, or {@code null}
     */
    WebSocketContext remove(WebSocketRegistryKey endpointKey) {
        if (endpointKey == null) {
            return null;
        }
        if (endpointKey.isConnectionScope()) {
            WebSocketContext previous = this.connectionContext;
            this.connectionContext = null;
            return previous;
        }
        ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> contexts = this.streamContexts;
        return contexts != null ? contexts.remove(endpointKey) : null;
    }

    void clear() {
        this.connectionContext = null;
        ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> contexts = this.streamContexts;
        if (contexts != null) {
            contexts.clear();
        }
    }

    /**
     * Return the websocket context bound to the specified endpoint.
     * @param endpointKey endpoint key
     * @return websocket context, or {@code null} when unbound
     */
    public WebSocketContext get(WebSocketRegistryKey endpointKey) {
        if (endpointKey == null) {
            return null;
        }
        if (endpointKey.isConnectionScope()) {
            return this.connectionContext;
        }
        ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> contexts = this.streamContexts;
        return contexts != null ? contexts.get(endpointKey) : null;
    }

    /**
     * Return the websocket context bound to the default connection-scope endpoint.
     */
    public WebSocketContext get() {
        return this.get(WebSocketRegistryKey.connectionScope());
    }

    //

    /**
     * Return the registry attached to the current or root protocol context.
     * @param context protocol context
     * @return registry, or {@code null} when absent
     */
    public static WebSocketRegistry get(ProtoContext context) {
        if (context == null) {
            return null;
        }

        WebSocketRegistry registry = context.context(WebSocketRegistry.class);
        if (registry != null) {
            return registry;
        }

        registry = context.rootContext(WebSocketRegistry.class);
        if (registry != null) {
            context.context(WebSocketRegistry.class, registry);
        }
        return registry;
    }

    /**
     * Return the registry attached to the given channel.
     * @param channel channel to inspect
     * @return registry, or {@code null} when absent
     */
    public static WebSocketRegistry get(SoChannel<?> channel) {
        if (channel == null) {
            return null;
        }
        return channel.findProtoContext(WebSocketRegistry.class);
    }

    private static void bindCloseCleanup(ProtoContext context, WebSocketRegistry registry) {
        if (context == null || registry == null || context.getChannel() == null) {
            return;
        }

        SoChannel<?> channel = context.getChannel();
        Object bound = channel.getAttribute(CLOSE_CLEANUP_BOUND_KEY);
        if (Boolean.TRUE.equals(bound)) {
            return;
        }

        channel.setAttribute(CLOSE_CLEANUP_BOUND_KEY, true);
        channel.onClose(ch -> {
            WebSocketRegistry currentRegistry = WebSocketRegistry.get(ch);
            if (currentRegistry != null) {
                currentRegistry.clear();
            }
        });
    }

    /**
     * Ensure the current protocol context carries a registry instance.
     * @param context protocol context
     * @return existing or newly created registry
     */
    static WebSocketRegistry ensure(ProtoContext context) {
        WebSocketRegistry registry = get(context);
        if (registry != null) {
            return registry;
        }

        registry = new WebSocketRegistry();
        context.context(WebSocketRegistry.class, registry);
        context.rootContext(WebSocketRegistry.class, registry);
        return registry;
    }

    private ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> streamContexts() {
        ConcurrentHashMap<WebSocketRegistryKey, WebSocketContext> contexts = this.streamContexts;
        if (contexts != null) {
            return contexts;
        }
        synchronized (this) {
            contexts = this.streamContexts;
            if (contexts == null) {
                contexts = new ConcurrentHashMap<>();
                this.streamContexts = contexts;
            }
            return contexts;
        }
    }

    static WebSocketRegistryKey resolveEndpointKey(ProtoContext context) {
        if (context == null) {
            return WebSocketRegistryKey.connectionScope();
        }

        PartitionKey partitionKey = PartitionKey.findKey(context);
        if (partitionKey == null || PartitionKey.defaultKey().equals(partitionKey)) {
            return WebSocketRegistryKey.connectionScope();
        }

        try {
            int streamId = Integer.parseInt(partitionKey.getKey());
            return streamId > 0 ? WebSocketRegistryKey.streamScope(streamId) : WebSocketRegistryKey.connectionScope();
        } catch (NumberFormatException e) {
            return WebSocketRegistryKey.connectionScope();
        }
    }

    private static WebSocketRegistryKey resolveEndpointKey(SoChannel<?> channel) {
        if (channel == null) {
            return WebSocketRegistryKey.connectionScope();
        }
        return WebSocketRegistryKey.connectionScope();
    }
}
