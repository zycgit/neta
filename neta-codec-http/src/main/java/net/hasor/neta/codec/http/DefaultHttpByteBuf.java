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
 * Each instance represents one non-terminal body chunk emitted after the header section has been
 * closed by {@link LastHttpHeaders}.
 */
public class DefaultHttpByteBuf implements HttpByteBuf {
    private int     streamId;
    private ByteBuf content;

    /**
     * Creates a body chunk with the specified payload.
     * @param content the chunk payload
     */
    public DefaultHttpByteBuf(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY : content;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    @Override
    public HttpByteBuf streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

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

    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
    }
}
