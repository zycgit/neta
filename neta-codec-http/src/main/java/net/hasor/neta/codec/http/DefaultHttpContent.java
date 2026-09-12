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
 * Default implementation of {@link HttpContent}.
 * <p>
 * Each instance represents a non-terminal content chunk emitted after the header
 * section has been closed by {@link LastHttpHeaders}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultHttpContent extends AbstractHttpObject<HttpContent> implements HttpContent {
    private ByteBuf content;

    /**
     * Create a content chunk with the specified payload.
     * @param content chunk payload
     */
    public DefaultHttpContent(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY : content;
    }

    @Override
    protected HttpContent self() {
        return this;
    }

    /**
     * Return the payload of this content chunk.
     * @return payload buffer
     */
    @Override
    public ByteBuf content() {
        return this.content;
    }

    @Override
    public ByteBuf transferContent() {
        ByteBuf current = this.content;
        this.content = null;
        return current;
    }

    @Override
    public String toString() {
        if (this.content != null) {
            return getClass().getSimpleName() + "(data: " + content.readableBytes() + " bytes)";
        } else {
            return getClass().getSimpleName() + "(released)";
        }
    }

    /**
     * Release the payload and associated state.
     */
    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.resetHttpObjectState();
    }
}
