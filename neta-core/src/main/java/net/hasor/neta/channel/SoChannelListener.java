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