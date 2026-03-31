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
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Central service context that manages all {@link NetChannel} and {@link NetListen} instances.
 * <p>It is owned by {@link NetManager} and exposes a unified runtime entry point to channels,
 * listeners, and external business code.</p>
 * <p>Callers can use it to read global configuration and the buffer allocator, query connection
 * state by channel ID, or subscribe to {@link PlayLoad} events on the event bus.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface SoContext {
    /**
     * Return the global configuration used by the current context.
     * <p>This is the configuration object chosen when {@link NetManager} was created, including
     * shared parameters such as thread counts, buffers, timeouts, and logging options.</p>
     */
    NetConfig getConfig();

    /**
     * Return the default {@link ByteBufAllocator} used by the current context.
     * <p>Channels reuse this allocator when creating read and write buffers. Application code may
     * also use the same allocator when it needs to allocate buffers directly, keeping memory usage
     * consistent with the runtime.</p>
     */
    ByteBufAllocator getByteBufAllocator();

    /**
     * Return the currently recorded remote address of the specified channel.
     * @param channelId target channel ID
     * @return the remote address, or {@code null} if the channel does not exist
     */
    SocketAddress getRemoteAddress(long channelId);

    /**
     * Return whether the specified channel is no longer usable.
     * <p>This method returns {@code true} when the channel does not exist or has already entered a
     * closed state.</p>
     * @param channelId target channel ID
     * @return {@code true} if the channel is missing or closed
     */
    boolean isClose(long channelId);

    /**
     * Find the channel object registered in the current context by channel ID.
     * @param channelId target channel ID
     * @return the matching {@link SoChannel}, or {@code null} if none exists
     */
    SoChannel<?> findChannel(long channelId);

    /**
     * Return the {@link NetManager} that owns the current context.
     * <p>Callers can use it to create connections, bind listeners, or trigger overall shutdown.</p>
     */
    NetManager getNetManager();

    /**
     * Subscribe to events on the specified channel.
     * <p>This overload uses {@link SubscribeMode#ASYNC} by default. Only {@link PlayLoad}
     * instances whose source channel ID equals {@code channelId} are delivered to the listener.</p>
     * @param channelId target channel ID
     * @param listener event listener; returns {@code null} when the listener is {@code null}
     * @return subscription handle that can later be used to unsubscribe
     */
    SubscribeHolder subscribe(long channelId, PlayLoadListener listener);

    /**
     * Subscribe to events on the specified channel using the given delivery mode.
     * <p>Only {@link PlayLoad} instances whose source channel ID equals {@code channelId} enter
     * this subscription.</p>
     * @param channelId target channel ID
     * @param mode delivery mode; {@code null} is treated as {@link SubscribeMode#ASYNC}
     * @param listener event listener; returns {@code null} when the listener is {@code null}
     * @return subscription handle that can later be used to unsubscribe
     */
    SubscribeHolder subscribe(long channelId, SubscribeMode mode, PlayLoadListener listener);

    /**
     * Subscribe to events using a custom filter.
     * <p>This overload uses {@link SubscribeMode#ASYNC} by default. Whenever a new
     * {@link PlayLoad} is published, the context first evaluates {@code select}; only events for
     * which it returns {@code true} are delivered to the listener.</p>
     * @param select event selection predicate
     * @param listener event listener; returns {@code null} when the listener is {@code null}
     * @return subscription handle that can later be used to unsubscribe
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener);

    /**
     * Subscribe to events using both a filter and an explicit delivery mode.
     * <p>This is the most complete subscription entry point. Callers can define both the event
     * matching rule and the delivery threading model.</p>
     * @param select event selection predicate
     * @param mode delivery mode; {@code null} is treated as {@link SubscribeMode#ASYNC}
     * @param listener event listener; returns {@code null} when the listener is {@code null}
     * @return subscription handle that can later be used to unsubscribe
     */
    SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener);
}