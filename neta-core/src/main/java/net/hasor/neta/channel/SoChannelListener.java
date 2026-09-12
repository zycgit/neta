/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
import java.util.EventListener;
/**
 * Callback interface for channel lifecycle events, for example channel-close notifications.
 * @param <T> concrete channel type handled by this listener
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoChannel#onClose
 */
@FunctionalInterface
public interface SoChannelListener<T extends SoChannel<?>> extends EventListener {
    /**
     * Handle a channel event.
     * @param channel channel that triggered the event
     */
    void onEvent(T channel);
}
