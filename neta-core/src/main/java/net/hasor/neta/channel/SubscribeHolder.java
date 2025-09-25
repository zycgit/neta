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
 * The SubscribeHolder interface represents a subscription holder in a channel.
 * Implementations of this interface are responsible for managing the lifecycle
 * of a subscription, including the ability to unsubscribe from the channel.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-09-24
 */
public interface SubscribeHolder {
    /**
     * Unsubscribes from the associated channel or event.
     * After calling this method, the subscription should be considered inactive,
     * and no further events will be received.
     */
    void unSubscribe();
}