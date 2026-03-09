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
 * Callback for channel lifecycle events, most commonly "channel closed".
 * <p>Register an instance via {@link SoChannel#onClose} to be notified when the specified
 * channel transitions to the closed state.  The callback is invoked at most once per
 * registration, on the I/O or task thread that performs the actual close operation.
 * <p>This is a {@link FunctionalInterface} compatible with lambda expressions:
 * <pre>
 * channel.onClose(ch -&gt; log.info("channel closed: " + ch.getChannelId()));
 * </pre>
 * @param <T> the concrete channel type that this listener handles
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoChannel#onClose
 */
@FunctionalInterface
public interface SoChannelListener<T extends SoChannel<?>> extends EventListener {
    /**
     * the channel.
     * @param channel the channel
     */
    void onEvent(T channel);
}