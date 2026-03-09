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
 * Handle returned by {@link SoChannel#subscribe} (and its overloads).
 * <p>Calling {@link #unSubscribe()} deactivates the registration and removes it from the
 * owning {@link SoContextService} listener list. For asynchronous subscriptions any queued,
 * not-yet-drained events are also discarded.
 * <p>The current implementation does not perform a dedicated automatic unregister step when a
 * channel closes; callers that need deterministic cleanup should explicitly call
 * {@link #unSubscribe()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-09-24
 * @see SoChannel#subscribe
 * @see SubscribeMode
 */
public interface SubscribeHolder {
    /**
     * Unsubscribes from the associated channel or event.
     * After calling this method, the subscription should be considered inactive,
     * and no further events will be received.
     */
    void unSubscribe();

    SubscribeMode getSubscribeMode();
}