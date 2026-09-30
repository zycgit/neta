/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link HttpByteBuf}.
 * <p>
 * Each instance represents a chunk of raw bytes passed through the HTTP pipeline.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultHttpByteBuf extends AbstractHttpObject<HttpByteBuf> implements HttpByteBuf {
    private static final RecycleObjectPool<DefaultHttpByteBuf> RECYCLER = new RecycleObjectPool<>(//
            DefaultHttpByteBuf::new, DefaultHttpByteBuf::resetState, DefaultHttpByteBuf::onRecycle);
    private              ByteBuf                               content;
    private              boolean                               active;

    private DefaultHttpByteBuf() {
    }

    private void resetState() {
        this.content = null;
        this.active = false;
        this.resetHttpObjectState();
    }

    private void onRecycle() {
        this.active = false;
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.resetHttpObjectState();
    }

    /**
     * Create a raw byte wrapper with the specified payload.
     * @param content chunk payload
     */
    public DefaultHttpByteBuf(ByteBuf content) {
        this(content, 0);
    }

    /**
     * Create a raw byte wrapper with the specified payload and stream identifier.
     * @param content chunk payload
     * @param streamId stream identifier
     */
    public DefaultHttpByteBuf(ByteBuf content, long streamId) {
        this.init(content, streamId);
    }

    /**
     * Create or reuse a raw byte wrapper with a stream identifier.
     * @param content chunk payload
     * @param streamId stream identifier
     * @return initialized wrapper
     */
    public static DefaultHttpByteBuf newInstance(ByteBuf content, long streamId) {
        DefaultHttpByteBuf wrapper = RECYCLER.get();
        wrapper.init(content, streamId);
        return wrapper;
    }

    private void init(ByteBuf content, long streamId) {
        this.resetHttpObjectState();
        this.content = content == null ? ByteBuf.EMPTY : content;
        this.active = true;
        this.streamId(streamId);
    }

    @Override
    protected HttpByteBuf self() {
        return this;
    }

    /**
     * Return the raw payload.
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
     * Release the payload buffer held by this object.
     */
    @Override
    public void release() {
        if (!this.active) {
            return;
        }
        RECYCLER.recycle(this);
    }
}
