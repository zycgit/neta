/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.io.IOException;
import java.io.InputStream;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufInputStream;

@FunctionalInterface
/**
 * Factory for opening a fresh input stream when a {@link StreamBody} is prepared.
 * <p>
 * Supplying a factory instead of a single cached stream lets request generation reopen content when
 * needed and keeps stream-backed bodies separate from fixed-length buffered bodies.
 */
public interface StreamSource {
    /** Wraps a {@link ByteBuf} as a stream source without releasing it on close. */
    static StreamSource of(ByteBuf byteBuf) {
        return of(byteBuf, false);
    }

    /** Wraps a {@link ByteBuf} as a stream source and optionally releases the buffer on close. */
    static StreamSource of(ByteBuf byteBuf, boolean releaseOnClose) {
        return () -> new ByteBufInputStream(byteBuf, releaseOnClose);
    }

    /** Opens the underlying stream used to read the request body content. */
    InputStream openStream() throws IOException;
}
