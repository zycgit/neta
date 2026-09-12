/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Callback invoked when a {@link PlayLoad} event is published on the channel event bus.
 * <p>Instances can be registered through {@link SoChannel#subscribe}, which returns a
 * {@link SubscribeHolder} that can later be used to unsubscribe. The delivery thread model depends
 * on the selected {@link SubscribeMode}:</p>
 * <ul>
 *   <li>{@link SubscribeMode#SYNC} invokes the callback inline on the same thread that triggers the
 *       event, usually an I/O thread. The callback must return quickly because blocking will slow
 *       that thread down.</li>
 *   <li>{@link SubscribeMode#ASYNC} schedules a separate task for execution. Events for the same
 *       subscriber are always delivered in order. This is the preferred mode when the listener may
 *       send data through NetChannel from inside the callback.</li>
 * </ul>
 * <p>Listeners attached to real network channels usually only observe inbound {@link PlayLoad}
 * events because outbound data has already been sent to the remote peer and is not fed back as a
 * local event.</p>
 * <p>For virtual pipelines, the listener may receive both inbound and outbound payloads.</p>
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
