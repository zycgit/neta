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
 * Subscription handle returned by {@link SoChannel#subscribe} and its overloads.
 * <p>Calling {@link #unSubscribe()} deactivates the current registration and removes it from the
 * listener list owned by {@link SoContextService}. For asynchronous subscriptions, any events that
 * have already been queued but not yet dispatched are discarded as well.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-09-24
 * @see SoChannel#subscribe
 * @see SubscribeMode
 */
public interface SubscribeHolder {
    /**
     * Cancel the subscription associated with the channel or event.
     * After this method returns, the subscription is considered inactive and no further events are delivered.
     */
    void unSubscribe();

    /** Return the delivery mode used by the current subscription. */
    SubscribeMode getSubscribeMode();
}