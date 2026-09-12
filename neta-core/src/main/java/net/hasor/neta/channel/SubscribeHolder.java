/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
