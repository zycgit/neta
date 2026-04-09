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
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link HttpByteBuf}.
 * <p>
 * Each instance represents a chunk of raw bytes passed through the HTTP pipeline.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultHttpByteBuf extends AbstractHttpObject<HttpByteBuf> implements HttpByteBuf {
    private ByteBuf content;

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
    public DefaultHttpByteBuf(ByteBuf content, int streamId) {
        this.content = content == null ? ByteBuf.EMPTY : content;
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
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.resetHttpObjectState();
    }
}
