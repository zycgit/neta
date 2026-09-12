/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Represents a content chunk within an HTTP message body.
 * <p>
 * {@link HttpContent} appears only after {@link LastHttpHeaders} has closed the initial header section. A message body may contain zero or more regular content chunks.
 * Messages with an explicit end boundary terminate with {@link LastHttpContent}, while responses delimited by connection close end when the connection closes.
 * If a caller needs to retain the payload reference beyond normal ownership, call retain directly on the {@link ByteBuf} returned by {@link #content()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 * @see LastHttpContent
 */
public interface HttpContent extends HttpObject {
    /**
     * Returns the payload carried by this content chunk.
     * @return payload buffer
     */
    ByteBuf content();

    /**
     * Transfers the current payload ownership out of this wrapper.
     * <p>
     * After transfer, this wrapper no longer owns the payload and later {@link #release()}
     * calls must not release the transferred buffer.
     * @return transferred payload, or {@code null} if this object no longer owns one
     */
    ByteBuf transferContent();
}
