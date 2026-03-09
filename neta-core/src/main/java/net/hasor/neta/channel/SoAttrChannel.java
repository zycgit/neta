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
 * Skeletal implementation of {@link SoChannel} providing the attribute map and
 * subscription plumbing shared by all concrete channel types.
 * <p>Responsibilities handled by this class:
 * <ul>
 *   <li><b>Attributes</b> – thread-safe key-value storage backed by a
 *       {@link ConcurrentHashMap}. Attributes persist for the full lifetime of the
 *       channel and are accessible from any thread.</li>
 *   <li><b>Subscriptions</b> – the four {@code subscribe} overloads are pre-wired to
 *       delegate to {@link SoContext#subscribe}, automatically AND-ing the caller’s
 *       filter predicate with a channel-ID check, so that only events originating from
 *       <em>this</em> channel are delivered to the listener.</li>
 * </ul>
 * <p>Subclasses must implement the remaining abstract members of {@link SoChannel}
 * (lifecycle, addressing, context access, and close semantics).
 * @param <T> the result type of the {@link SoChannel#close()} future
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoChannel
 */
public abstract class SoAttrChannel<T> implements SoChannel<T> {
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    @Override
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    @Override
    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }

    /** Subscribes to all {@link PlayLoad} events originating from this channel. */
    @Override
    public SubscribeHolder subscribe(PlayLoadListener listener) {
        return this.subscribe(t -> true, SubscribeMode.ASYNC, listener);
    }

    @Override
    public SubscribeHolder subscribe(SubscribeMode mode, PlayLoadListener listener) {
        return this.subscribe(t -> true, mode, listener);
    }

    /** Subscribes to events from this channel that satisfy {@code select}. */
    @Override
    public SubscribeHolder subscribe(Predicate<PlayLoad> select, PlayLoadListener listener) {
        return this.subscribe(select, SubscribeMode.ASYNC, listener);
    }

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