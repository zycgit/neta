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
 * Default implementation of {@link HttpContent}.
 * <p>
 * Each instance represents one non-terminal body chunk emitted after the header section has been
 * closed by {@link LastHttpHeaders}.
 */
public class DefaultHttpContent implements HttpContent {
    private int     streamId;
    private ByteBuf content;
    private boolean bad;
    private String  badReason;

    /**
     * Creates a body chunk with the specified payload.
     * @param content the chunk payload
     */
    public DefaultHttpContent(ByteBuf content) {
        this.content = content == null ? ByteBuf.EMPTY : content;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    @Override
    public HttpContent streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public ByteBuf content() {
        return this.content;
    }

    @Override
    public boolean isBad() {
        return this.bad;
    }

    @Override
    public String badReason() {
        return this.badReason;
    }

    @Override
    public HttpContent markBad(String reason) {
        this.bad = true;
        this.badReason = reason;
        return this;
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
        this.streamId = 0;
        this.bad = false;
        this.badReason = null;
    }
}
