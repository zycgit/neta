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
 * Wraps raw bytes that pass through the HTTP pipeline without being parsed as a request or response.
 * <p>
 * This type is primarily used in HTTP transparent mode. Decoders wrap inbound {@link ByteBuf} instances as {@link HttpByteBuf},
 * while encoders forward the payload returned by {@link #content()} directly without adding HTTP framing semantics.
 * <p>
 * If a caller needs to retain the payload reference beyond normal ownership, call retain directly on the {@link ByteBuf} returned by {@link #content()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public interface HttpByteBuf extends HttpObject {
    /**
     * Returns the raw payload carried by this pass-through object.
     * @return raw payload buffer
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
