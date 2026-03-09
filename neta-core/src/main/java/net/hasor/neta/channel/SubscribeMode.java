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
 * Delivery mode for {@link PlayLoadListener} subscriptions.
 * <ul>
 *   <li>{@link #SYNC} – {@link PlayLoadListener#onEvent} is called <em>inline</em> on
 *       the same thread (I/O or task worker) that triggered the event.  Choose this for
 *       minimal latency, but ensure the callback returns quickly: blocking will stall
 *       the triggering thread.</li>
 *   <li>{@link #ASYNC} – the event is queued and dispatched to a
 *       {@link SoEventExecutor} worker thread.  Events for the same subscriber are
 *       always delivered in FIFO order.  Choose this for callbacks that may block, do
 *       I/O, or interact with external systems.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see PlayLoadListener
 * @see SoChannel#subscribe
 */
public enum SubscribeMode {
    SYNC,
    ASYNC,
}