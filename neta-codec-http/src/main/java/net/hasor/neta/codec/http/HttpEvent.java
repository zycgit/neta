/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.neta.channel.SoEventData;
/**
 * Defines the common contract for HTTP-related user events.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface HttpEvent extends SoEventData {
    /**
     * Returns the stream identifier associated with this event.
     * @return stream identifier
     */
    long streamId();

    /**
     * Sets the stream identifier associated with this event.
     * @param streamId stream identifier
     * @return this event object
     */
    HttpEvent streamId(long streamId);

    /**
     * Releases resources held by this event.
     */
    void release();
}
