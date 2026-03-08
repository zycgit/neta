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
 * Represents a network channel abstraction.
 * Provides methods for managing channel state, attributes, and message subscriptions.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface SoChannel<T> {
    /** the unique channel ID. */
    long getChannelId();

    /** the time when the channel was created/accepted. */
    long getCreatedTime();

    /** the last time data was sent or received on this channel. */
    long getLastActiveTime();

    /** Returns true if this channel is a {@link NetListen} channel. */
    boolean isListen();

    /** Returns true if this channel is a server-side accepted {@link NetChannel}. */
    boolean isServer();

    /** Returns true if this channel is a client-side accepted {@link NetChannel}. */
    boolean isClient();

    /** the local {@link SocketAddress} of this channel. */
    SocketAddress getLocalAddr();

    /** the remote {@link SocketAddress} of this channel. */
    SocketAddress getRemoteAddr();

    /** the context with this channel. */
    SoContext getContext();

    /** the configuration for this channel. */
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

    /** Immediately closes this channel. */
    void closeNow();

    /** Registers a listener to be notified when this channel is closed. */
    void onClose(SoChannelListener<SoChannel<?>> listener);

    /** Returns true if this channel is closed. */
    boolean isClose();

    /** Sets an attribute for this channel using the specified key. */
    void setAttribute(String key, Object value);

    /** Gets the attribute associated with the specified key for this channel. */
    Object getAttribute(String key);

    /** Gets the attachment for the specified ProtoContext type. */
    <V> V findProtoContext(Class<V> serviceType);

    /** Subscribes to messages belonging to this channel. */
    SubscribeHolder subscribe(PlayLoadListener listener);

    /** Subscribes to messages belonging to this channel using the specified delivery mode. */
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