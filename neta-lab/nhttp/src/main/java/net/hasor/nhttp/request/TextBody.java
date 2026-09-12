/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.request;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import net.hasor.neta.bytebuf.ByteBuf;

/** Simple in-memory textual body. */
public final class TextBody extends ContentBody {
    private final String  text;
    private final Charset charset;
    private final String  mediaType;

    private TextBody(Builder builder) {
        this.text = builder.text == null ? "" : builder.text;
        this.charset = builder.charset == null ? StandardCharsets.UTF_8 : builder.charset;
        this.mediaType = builder.mediaType == null ? "text/plain" : builder.mediaType;
    }

    /** Starts a text body builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected PreparedBody doPrepare() {
        byte[] bytes = this.text.getBytes(this.charset);
        return PreparedBody.single(ByteBuf.wrap(bytes), this.mediaType + "; charset=" + ContentBodySupport.charsetName(this.charset), (long) bytes.length);
    }

    public static final class Builder {
        private String  text;
        private Charset charset   = StandardCharsets.UTF_8;
        private String  mediaType = "text/plain";

        /** Sets the text payload. */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Sets the charset appended to the emitted media type. */
        public Builder charset(Charset charset) {
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            return this;
        }

        /** Sets the base media type, defaulting to {@code text/plain}. */
        public Builder mediaType(String mediaType) {
            this.mediaType = mediaType == null ? "text/plain" : mediaType;
            return this;
        }

        /** Builds the immutable text body. */
        public TextBody build() {
            return new TextBody(this);
        }
    }
}
