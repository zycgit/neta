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
/**
 * Callback invoked whenever a {@link PlayLoad} event is published on the channel event bus.
 * <p>Register an instance via {@link SoChannel#subscribe} (returns a
 * {@link SubscribeHolder} for later cancellation).  The delivery threading model
 * depends on the {@link SubscribeMode} chosen at registration time:
 * <ul>
 *   <li>{@link SubscribeMode#SYNC} – {@link #onEvent} is called inline on the same thread
 *       (I/O or task worker) that triggered the event.  The callback must return quickly;
 *       blocking will stall that thread.</li>
 *   <li>{@link SubscribeMode#ASYNC} – the event is queued and dispatched on a
 *       {@link SoEventExecutor} worker thread.  Events for the same subscriber are always
 *       delivered in order.  Choose this for callbacks that may block or touch external
 *       systems.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see PlayLoad
 * @see SubscribeMode
 * @see SoChannel#subscribe
 */
@FunctionalInterface
public interface PlayLoadListener extends java.util.EventListener {
    /** Called when a {@link PlayLoad} event is published. */
    void onEvent(PlayLoad data);
}