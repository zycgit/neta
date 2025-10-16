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
 * A message data in the message bus
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
final class SoUserEventObject implements SoUserEvent {
    private final Class<?>     eventType;
    private final Object       data;
    private final SoChannel<?> source;

    SoUserEventObject(Class<?> eventType, Object data, SoChannel<?> source) {
        this.eventType = eventType;
        this.data = data;
        this.source = source;
    }

    /**
     * Creates a successful payload with the specified source channel and data.
     * @param source the originating SoChannel
     * @param event the message data
     * @return a new PlayLoad instance containing the data
     */
    public static SoUserEvent of(SoChannel<?> source, Class<?> eventType, Object event) {
        return new SoUserEventObject(eventType, event, source);
    }

    @Override
    public SoChannel<?> getSource() {
        return this.source;
    }

    @Override
    public Class<?> getEventType() {
        return this.eventType;
    }

    @Override
    public Object getData() {
        return this.data;
    }
}