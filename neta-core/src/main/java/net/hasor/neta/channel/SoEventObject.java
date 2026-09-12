/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel;
/**
 * Default {@link SoEvent} implementation that carries a typed network event.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public final class SoEventObject implements SoEvent {
    private final Class<?>     eventType;
    private final Object       data;
    private final SoChannel<?> source;

    SoEventObject(Class<?> eventType, Object data, SoChannel<?> source) {
        this.eventType = eventType;
        this.data = data;
        this.source = source;
    }

    /** Create a network event from the given source channel, event type, and event data. */
    public static SoEvent of(SoChannel<?> source, Class<?> eventType, Object event) {
        return new SoEventObject(eventType, event, source);
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

    @Override
    public String toString() {
        return "SoEventObject{" + "eventType=" + (this.eventType != null ? this.eventType.getSimpleName() : "null") + ", data=" + this.data + '}';
    }
}
