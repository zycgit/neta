/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.util.Objects;

import net.hasor.neta.bytebuf.ByteBuf;

/** Fixed-length body backed directly by an existing {@link ByteBuf}. */
public final class ByteBufBody extends ContentBody {
    private final ByteBuf byteBuf;
    private final String  defaultContentType;

    private ByteBufBody(Builder builder) {
        this.byteBuf = Objects.requireNonNull(builder.byteBuf, "byteBuf");
        this.defaultContentType = builder.defaultContentType;
    }

    /** Starts a {@link ByteBuf}-backed body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() {
        return PreparedBody.single(this.byteBuf, this.defaultContentType, (long) this.byteBuf.readableBytes());
    }

    public static final class Builder {
        private ByteBuf byteBuf;
        private String  defaultContentType;

        /** Sets the source buffer that will be written as-is. */
        public Builder byteBuf(ByteBuf byteBuf) {
            this.byteBuf = byteBuf;
            return this;
        }

        /** Sets the default media type advertised when the request does not override it. */
        public Builder contentType(String defaultContentType) {
            this.defaultContentType = defaultContentType;
            return this;
        }

        /** Builds the immutable {@link ByteBuf}-backed body. */
        public ByteBufBody build() {
            return new ByteBufBody(this);
        }
    }
}
