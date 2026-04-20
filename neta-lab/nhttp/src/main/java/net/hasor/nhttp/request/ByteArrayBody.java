/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.request;

import net.hasor.neta.bytebuf.ByteBuf;

/** Fixed-length body backed by an in-memory byte array snapshot. */
public final class ByteArrayBody extends ContentBody {
    private final byte[] bodyBytes;
    private final String defaultContentType;

    private ByteArrayBody(Builder builder) {
        this.bodyBytes = builder.bodyBytes == null ? new byte[0] : builder.bodyBytes.clone();
        this.defaultContentType = builder.defaultContentType;
    }

    /** Starts a byte-array body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() {
        byte[] bytes = this.bodyBytes.clone();
        return PreparedBody.single(ByteBuf.wrap(bytes), this.defaultContentType, (long) bytes.length);
    }

    public static final class Builder {
        private byte[] bodyBytes;
        private String defaultContentType;

        /** Sets the payload bytes using a defensive copy. */
        public Builder bodyBytes(byte[] bodyBytes) {
            this.bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes.clone();
            return this;
        }

        /** Sets the default media type advertised when the request does not override it. */
        public Builder contentType(String defaultContentType) {
            this.defaultContentType = defaultContentType;
            return this;
        }

        /** Builds the immutable byte-array body. */
        public ByteArrayBody build() {
            return new ByteArrayBody(this);
        }
    }
}