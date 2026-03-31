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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Skeleton implementation of {@link SoChannel} that provides shared attribute storage and
 * subscription forwarding for all concrete channel types.
 * <p>This class handles:</p>
 * <ul>
 *   <li><b>Attributes</b>: thread-safe key/value storage backed by {@link ConcurrentHashMap}.
 *       Attributes remain available for the full lifetime of the channel and may be accessed from
 *       any thread.</li>
 *   <li><b>Subscriptions</b>: the four {@code subscribe} overloads are pre-wired to
 *       {@link SoContext#subscribe}, automatically AND-ing the caller's filter predicate with the
 *       current channel ID so only events originating from <b>this</b> channel are delivered to the
 *       listener.</li>
 * </ul>
 * <p>Subclasses must implement the remaining abstract members of {@link SoChannel}, including
 * lifecycle, address information, context access, and close semantics.</p>
 * @param <T> result type of the Future returned by {@link SoChannel#close()}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoChannel
 */
public abstract class SoAttrChannel<T> implements SoChannel<T> {
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * Set a named attribute on the current channel.
     * @param key attribute name
     * @param value attribute value
     */
    @Override
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    /**
     * Read a named attribute from the current channel.
     * @param key attribute name
     * @return attribute value, or {@code null} if none exists
     */
    @Override
    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }

    /** Subscribe to all {@link PlayLoad} events originating from the current channel. */
    @Override
    public SubscribeHolder subscribe(PlayLoadListener listener) {
        return this.subscribe(t -> true, SubscribeMode.ASYNC, listener);
    }

    /**
     * Subscribe to all {@link PlayLoad} events originating from the current channel using the given delivery mode.
     * @param mode subscription delivery mode
     * @param listener event listener
     * @return subscription handle
     */
    @Override
    public SubscribeHolder subscribe(SubscribeMode mode, PlayLoadListener listener) {
        return this.subscribe(t -> true, mode, listener);
    }

    /** Subscribe to events from the current channel that satisfy {@code select}. */
    @Override
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener) {
        return this.subscribe(select, SubscribeMode.ASYNC, listener);
    }

    /**
     * Subscribe to filtered events from the current channel using the specified delivery mode.
     * @param select event filter predicate; {@code null} means no additional filtering
     * @param mode subscription delivery mode
     * @param listener event listener
     * @return subscription handle
     */
    @Override
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener) {
        Predicate<PlayLoad> baseSelect = select == null ? new Predicate<PlayLoad>() {
            @Override
            public boolean test(PlayLoad playLoad) {
                return true;
            }
        } : select;
        Predicate<PlayLoad> predicate = baseSelect.and(t -> t.getSource().getChannelId() == this.getChannelId());
        return this.getContext().subscribe(predicate, mode, listener);
    }
}