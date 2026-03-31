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
 * Common abstraction for all Neta channel types, including listening endpoints ({@link NetListen})
 * and active connections ({@link NetChannel}).
 * <p>Channels move through two stages:</p>
 * <pre>
 * [Created] ──bind/connect──► [Active] ──close/closeNow──► [Closed]
 * </pre>
 * <p>The current stage can be checked through {@link #isClose()}. Listening channels are
 * identified by {@link #isListen()}, while active connections are distinguished by
 * {@link #isServer()} for server-accepted channels and {@link #isClient()} for client-initiated
 * ones.</p>
 * <h3>Attributes</h3>
 * Each channel carries a thread-safe key/value map, see {@link #setAttribute} and
 * {@link #getAttribute}, for arbitrary application data that remains valid for the lifetime of the
 * channel.
 * <h3>Event Bus</h3>
 * The {@code subscribe} methods let callers observe decoded pipeline events ({@link PlayLoad})
 * without modifying the handler chain. The subscription scope is limited to the current channel and
 * is removed automatically when the channel closes.
 * <p>Payload objects remain owned by the pipeline or transport that produced them. Unless the
 * concrete payload type states otherwise, listeners should treat {@link PlayLoad#getData()} as a
 * one-shot observation. In particular, outbound events from virtual channels may expose the same
 * {@code ByteBuf} instance still owned by the current send operation. If a listener needs to keep
 * using that buffer after the callback returns, it must retain or copy it inside the callback.</p>
 * <h3>Close</h3>
 * <ul>
 *   <li>{@link #close()}: graceful close, flush the send queue first and then close.</li>
 *   <li>{@link #closeNow()}: immediate close, discard the send queue and close right away.</li>
 * </ul>
 * <h3>Thread Safety</h3>
 * Implementations guarantee that all methods on this interface are safe to call from any thread.
 * Specific operations may still have ordering constraints; see the Javadoc of concrete implementations.
 * @param <T> result type of the Future returned by {@link #close()}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see NetChannel
 * @see NetListen
 * @see PlayLoad
 */
public interface SoChannel<T> {
    /** Return the globally unique channel ID. */
    long getChannelId();

    /** Return the creation time or the time when the channel was accepted. */
    long getCreatedTime();

    /** Return the time of the most recent send or receive activity. */
    long getLastActiveTime();

    /** Return true if this channel is a {@link NetListen}. */
    boolean isListen();

    /** Return true when this channel plays the server-side role. */
    boolean isServer();

    /** Return true when this channel plays the client-side role. */
    boolean isClient();

    /** Return the local address. */
    SocketAddress getLocalAddr();

    /** Return the remote address. */
    SocketAddress getRemoteAddr();

    /** Return the channel context. */
    SoContext getContext();

    /** Return the channel configuration. */
    SoConfig getConfig();

    /**
     * Gracefully close the current channel.
     * <ul>
     *   <li>If this is a listening channel, stop listening.</li>
     *   <li>If this is a socket channel, close it only after all pending data has been written.</li>
     * </ul>
     * Only the first call to either {@link #closeNow()} or {@link #close()} takes effect.
     * @return Future representing the close operation
     */
    Future<T> close();

    /** Close the current channel immediately. */
    void closeNow();

    /** Register a listener to be notified when the channel closes. */
    void onClose(SoChannelListener<SoChannel<?>> listener);

    /** Return true if the current channel is already closed. */
    boolean isClose();

    /** Set a channel attribute. */
    void setAttribute(String key, Object value);

    /** Return a channel attribute by key. */
    Object getAttribute(String key);

    /** Find a context attachment by type. */
    <V> V findProtoContext(Class<V> serviceType);

    /**
     * Subscribe to events emitted by the current channel.
     * <p>Ownership of emitted {@link PlayLoad} data remains with the channel pipeline. If a
     * listener needs to keep a reference-counted payload after the callback returns, it should
     * retain or copy it before returning.</p>
     */
    SubscribeHolder subscribe(PlayLoadListener listener);

    /**
     * Subscribe to current-channel events using the specified delivery mode.
     * <p>Ownership of emitted {@link PlayLoad} data remains with the channel pipeline. If a
     * listener needs to keep a reference-counted payload after the callback returns, it should
     * retain or copy it before returning.</p>
     */
    SubscribeHolder subscribe(SubscribeMode mode, PlayLoadListener listener);

    /**
     * Subscribe to messages belonging to the current channel using the given predicate to filter events.
     * <p>Ownership of emitted {@link PlayLoad} data remains with the channel pipeline. If a
     * listener needs to keep a reference-counted payload after the callback returns, it should
     * retain or copy it before returning.</p>
     * @param select event filter predicate
     * @param listener listener that handles filtered events
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener);

    /**
     * Subscribe to messages belonging to the current channel using both a filter and a delivery mode.
     * <p>Ownership of emitted {@link PlayLoad} data remains with the channel pipeline. If a
     * listener needs to keep a reference-counted payload after the callback returns, it should
     * retain or copy it before returning.</p>
     * @param select event filter predicate
     * @param mode delivery mode
     * @param listener listener that handles filtered events
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener);
}