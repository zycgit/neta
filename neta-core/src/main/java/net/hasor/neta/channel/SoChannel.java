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
package net.hasor.neta.channel;
import java.net.SocketAddress;
import java.util.function.Predicate;
import net.hasor.cobble.concurrent.future.Future;

/**
 * Common abstraction for all Neta channel types: listening ports ({@link NetListen})
 * and active socket connections ({@link NetChannel}).
 * <p>A channel passes through two phases:
 * <pre>
 * [Created] ──bind/connect──► [Active] ──close/closeNow──► [Closed]
 * </pre>
 * Query the current phase with {@link #isClose()}.  Listening channels respond to
 * {@link #isListen()}; active connections respond to {@link #isServer()} (server-side
 * accepted) and {@link #isClient()} (client-side initiated).
 * <h3>Attributes</h3>
 * Every channel carries a thread-safe key-value map (see {@link #setAttribute} /
 * {@link #getAttribute}) for attaching arbitrary application data that persists
 * for the lifetime of the channel.
 * <h3>Message bus</h3>
 * The {@code subscribe} family of methods lets callers observe decoded pipeline events
 * ({@link PlayLoad}) without modifying the handler chain. Subscriptions are scoped to
 * this channel and are automatically removed when the channel closes.
 * <h3>Closing</h3>
 * <ul>
 *   <li>{@link #close()} – graceful: flushes the outbound queue, then closes.</li>
 *   <li>{@link #closeNow()} – immediate: discards the outbound queue and closes at once.</li>
 * </ul>
 * <h3>Thread safety</h3>
 * Implementations guarantee that all methods on this interface are safe to call from
 * any thread. Individual operations may still have ordering caveats; see the specific
 * implementation Javadoc for details.
 * @param <T> the result type of the {@link #close()} future
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see NetChannel
 * @see NetListen
 * @see PlayLoad
 */
public interface SoChannel<T> {
    /** Returns the unique channel ID. */
    long getChannelId();

    /** Returns the creation or accept time. */
    long getCreatedTime();

    /** Returns the last send or receive time. */
    long getLastActiveTime();

    /** Returns true if this channel is a {@link NetListen} channel. */
    boolean isListen();

    /** Returns true if this channel is a server-side accepted {@link NetChannel}. */
    boolean isServer();

    /** Returns true if this channel is a client-side accepted {@link NetChannel}. */
    boolean isClient();

    /** Returns the local address. */
    SocketAddress getLocalAddr();

    /** Returns the remote address. */
    SocketAddress getRemoteAddr();

    /** Returns the channel context. */
    SoContext getContext();

    /** Returns the channel configuration. */
    SoConfig getConfig();

    /**
     * close this channel.
     * <ul>
     *   <li>If the channel is a listening channel, it will stop listening.</li>
     *   <li>If the channel is a socket channel, it will close after all data is written.</li>
     * </ul>
     * The {@link #closeNow()} and {@link #close()} methods are only effective if called first.
     * @return a Future representing the close operation
     */
    Future<T> close();

    /** Closes this channel immediately. */
    void closeNow();

    /** Registers a listener to be notified when this channel is closed. */
    void onClose(SoChannelListener<SoChannel<?>> listener);

    /** Returns true when this channel is closed. */
    boolean isClose();

    /** Sets a channel attribute. */
    void setAttribute(String key, Object value);

    /** Returns a channel attribute by key. */
    Object getAttribute(String key);

    /** Finds a context attachment by type. */
    <V> V findProtoContext(Class<V> serviceType);

    /** Subscribes to events emitted by this channel. */
    SubscribeHolder subscribe(PlayLoadListener listener);

    /** Subscribes to channel events with the given delivery mode. */
    SubscribeHolder subscribe(SubscribeMode mode, PlayLoadListener listener);

    /**
     * Subscribes to messages belonging to this channel and filters events using the provided predicate.
     * @param select predicate to filter events
     * @param listener listener to handle filtered events
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener);

    /**
     * Subscribes to messages belonging to this channel with the specified filter and delivery mode.
     * @param select predicate to filter events
     * @param mode delivery mode
     * @param listener listener to handle filtered events
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener);
}