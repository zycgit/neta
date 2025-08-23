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
package net.hasor.neta.handler;
import net.hasor.neta.channel.SoChannel;

/**
 * event bus
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public interface EventBus {
    /** all event */
    String TOPIC_ALL     = "/";
    /** channel event */
    String TOPIC_CHANNEL = "/CHANNEL/";

    /**
     * trigger event form channel
     * @param channel event channel
     * @param obj event data
     */
    void triggerReceive(SoChannel<?> channel, Object obj);

    /**
     * trigger event form channel
     * @param channel event channel
     * @param error event error
     * @param isRcv is receive event
     */
    void triggerError(SoChannel<?> channel, Throwable error, boolean isRcv);

    //

    /**
     * trigger event
     * @param topic event topic
     * @param data event data
     */
    void trigger(String topic, Object data);

    /**
     * subscribe event
     * @param topic event topic
     * @param listener event listener
     */
    void subscribe(String topic, EventListener listener);
}
