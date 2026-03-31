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
 * Event delivery mode for {@link PlayLoadListener} subscriptions.
 * <ul>
 *   <li>{@link #SYNC}: {@link PlayLoadListener#onEvent} is invoked inline on the same thread that
 *       triggers the event, typically an I/O thread or worker thread. It is suitable for the
 *       lowest-latency cases, but the callback must return quickly to avoid blocking the trigger
 *       thread.</li>
 *   <li>{@link #ASYNC}: events are queued first and then dispatched asynchronously by a worker
 *       thread in {@link SoTaskExecutor}. Events for the same subscriber are always delivered in
 *       FIFO order. This mode is appropriate for callbacks that may block, perform I/O, or talk to
 *       external systems.</li>
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