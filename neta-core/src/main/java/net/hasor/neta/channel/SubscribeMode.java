/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
